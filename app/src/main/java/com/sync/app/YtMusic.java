package com.sync.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * YouTube Music(InnerTube WEB_REMIX) 클라이언트.
 *
 * 일반 유튜브 "동영상 검색" 은 앨범/아티스트 메타데이터가 없고 컴필레이션·커버 영상이 섞인다.
 * YouTube Music 의 곡 검색 / 앨범 페이지 / 라디오(next) / 홈·차트 선반은 정식 "곡" 단위 데이터를 주므로
 *   - 곡의 앨범 이름·앨범 ID, 아티스트 ID, 정사각형 앨범 커버
 *   - 앨범 → 수록곡 목록
 *   - 곡 기반 라디오(연관곡) 추천
 *   - 신곡 앨범 / 차트
 * 를 얻는다.
 *
 * 응답 구조는 유튜브가 자주 바꾸므로 고정 경로 대신 "키로 재귀 탐색" 방식으로 파싱하고,
 * 어떤 단계에서 실패해도 예외는 호출자에게 던져 MainActivity 가 기존(레거시) 검색으로 폴백한다.
 * 모든 결과는 짧은 TTL 의 LRU 캐시에 담아 같은 요청을 다시 보내지 않는다.
 */
final class YtMusic {

    private static final String BASE = "https://music.youtube.com/youtubei/v1/";
    private static final String KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final String CLIENT_VERSION = "1.20241127.01.00";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36";

    /** 검색 필터: 곡 / 앨범 */
    private static final String P_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";
    private static final String P_ALBUMS = "EgWKAQIYAWoKEAkQBRAKEAMQBA==";

    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int CACHE_MAX = 48;

    private static final Pattern DUR_RX = Pattern.compile("^\\d{1,2}(:\\d{2}){1,2}$");
    private static final Pattern YEAR_RX = Pattern.compile("^(19|20)\\d{2}$");
    private static final Pattern SIZE_RX = Pattern.compile("=w\\d+-h\\d+.*$");
    private static final Set<String> TYPE_WORDS = new HashSet<>(Arrays.asList(
            "song", "video", "single", "ep", "album", "playlist", "artist",
            "노래", "동영상", "싱글", "앨범", "플레이리스트", "아티스트", "곡"));

    private final OkHttpClient http;

    private final Map<String, Object[]> cache = new LinkedHashMap<String, Object[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
            return size() > CACHE_MAX;
        }
    };

    YtMusic(OkHttpClient http) {
        this.http = http;
    }

    /* ════════════════════════════════════════════
       PUBLIC API  (모두 블로킹 — 반드시 백그라운드 스레드에서 호출)
    ════════════════════════════════════════════ */

    /** 곡 검색 → [{id,title,channel,dur,thumb,cover,album,albumId,artistId}] */
    JSONArray searchSongs(String query, boolean fresh) throws Exception {
        JSONObject body = base("KR");
        body.put("query", query);
        body.put("params", P_SONGS);
        JSONObject json = cachedPost("search", body, "s:" + query, fresh);

        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONObject it : collect(json, "musicResponsiveListItemRenderer", null)) {
            JSONObject t = songFromListItem(it, null);
            if (t == null || !seen.add(t.optString("id"))) continue;
            out.put(t);
            if (out.length() >= 20) break;
        }
        return out;
    }

    /** 앨범 검색 → [{id(browseId),title,artist,year,cover}] */
    JSONArray searchAlbums(String query, boolean fresh) throws Exception {
        JSONObject body = base("KR");
        body.put("query", query);
        body.put("params", P_ALBUMS);
        JSONObject json = cachedPost("search", body, "a:" + query, fresh);

        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONObject it : collect(json, "musicResponsiveListItemRenderer", null)) {
            String bid = str(path(it, "navigationEndpoint", "browseEndpoint", "browseId"));
            if (!bid.startsWith("MPRE") || !seen.add(bid)) continue;
            JSONObject a = new JSONObject();
            a.put("kind", "album");
            a.put("id", bid);
            a.put("title", runsText(flex(it, 0)));
            fillArtistYear(a, flexRuns(it, 1));
            a.put("cover", sq(bestThumb(it), 300));
            out.put(a);
            if (out.length() >= 12) break;
        }
        return out;
    }

    /**
     * 앨범/플레이리스트 상세. browseId 가 MPRE 로 시작하면 앨범, 그 외(VL…)는 플레이리스트.
     * → {id,title,artist,year,cover,isAlbum,tracks:[...]}
     */
    JSONObject browseDetail(String browseId, boolean fresh) throws Exception {
        JSONObject body = base("KR");
        body.put("browseId", browseId);
        JSONObject json = cachedPost("browse", body, "b:" + browseId, fresh);

        boolean isAlbum = browseId.startsWith("MPRE");
        JSONObject header = firstOf(json, "musicResponsiveHeaderRenderer",
                "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
                "musicImmersiveHeaderRenderer");

        JSONObject res = new JSONObject();
        res.put("id", browseId);
        res.put("isAlbum", isAlbum);
        String title = "", cover = "";
        JSONObject meta = new JSONObject();
        if (header != null) {
            title = runsText(header.optJSONObject("title"));
            cover = sq(bestThumb(header), 544);
            List<JSONObject> runs = new ArrayList<>();
            addRuns(runs, header.optJSONObject("straplineTextOne"));
            addRuns(runs, header.optJSONObject("subtitle"));
            fillArtistYear(meta, runs);
        }
        res.put("title", title);
        res.put("cover", cover);
        res.put("artist", meta.optString("artist", ""));
        res.put("year", meta.optString("year", ""));
        res.put("artistId", meta.optString("artistId", ""));

        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONObject it : collect(json, "musicResponsiveListItemRenderer", null)) {
            JSONObject t = songFromListItem(it, isAlbum ? meta : null);
            if (t == null) continue;
            if (isAlbum) {
                t.put("album", title);
                t.put("albumId", browseId);
                if (t.optString("cover").isEmpty()) t.put("cover", sq(cover, 300));
                if (t.optString("channel").isEmpty()) t.put("channel", meta.optString("artist", ""));
            }
            if (!seen.add(t.optString("id"))) continue;
            tracks.put(t);
        }
        res.put("tracks", tracks);
        if (tracks.length() == 0 && title.isEmpty()) throw new IOException("empty detail");
        return res;
    }

    /**
     * 곡 기반 라디오(연관곡). 첫 항목은 보통 시드 곡 자신 → 앨범 정보 보강에도 쓴다.
     * → [{id,title,channel,dur,thumb,cover,album,albumId,artistId}]
     */
    JSONArray related(String videoId, boolean fresh) throws Exception {
        JSONObject body = base("KR");
        body.put("videoId", videoId);
        body.put("playlistId", "RDAMVM" + videoId);
        body.put("params", "wAEB");
        body.put("isAudioOnly", true);
        JSONObject json = cachedPost("next", body, "r:" + videoId, fresh);

        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        Set<String> skip = new HashSet<>(Arrays.asList("counterpart"));
        for (JSONObject it : collect(json, "playlistPanelVideoRenderer", skip)) {
            String vid = it.optString("videoId", "");
            if (vid.isEmpty() || !seen.add(vid)) continue;
            JSONObject t = new JSONObject();
            t.put("id", vid);
            t.put("title", runsText(it.optJSONObject("title")));
            List<JSONObject> runs = new ArrayList<>();
            addRuns(runs, it.optJSONObject("longBylineText"));
            if (runs.isEmpty()) addRuns(runs, it.optJSONObject("shortBylineText"));
            fillTrackMeta(t, runs);
            t.put("dur", parseDur(runsText(it.optJSONObject("lengthText"))));
            String cv = bestThumb(it);
            t.put("cover", sq(cv, 300));
            t.put("thumb", sq(cv, 300));
            out.put(t);
            if (out.length() >= 40) break;
        }
        return out;
    }

    /**
     * 홈/차트/신곡 같은 브라우즈 페이지의 선반 목록.
     * → [{title, items:[{kind:'song'|'album'|'playlist'|'artist', id, title, artist, cover, ...}]}]
     */
    JSONArray shelves(String browseId, String params, boolean fresh) throws Exception {
        JSONObject body = base("KR");
        body.put("browseId", browseId);
        if (params != null && !params.isEmpty()) body.put("params", params);
        JSONObject json = cachedPost("browse", body, "h:" + browseId + ":" + params, fresh);

        JSONArray out = new JSONArray();
        List<JSONObject> containers = new ArrayList<>();
        containers.addAll(collect(json, "musicCarouselShelfRenderer", null));
        containers.addAll(collect(json, "musicShelfRenderer", null));
        containers.addAll(collect(json, "gridRenderer", null));

        for (JSONObject sh : containers) {
            String title = "";
            JSONObject hd = sh.optJSONObject("header");
            if (hd != null) {
                JSONObject h1 = firstOf(hd, "musicCarouselShelfBasicHeaderRenderer", "gridHeaderRenderer");
                if (h1 != null) title = runsText(h1.optJSONObject("title"));
            }
            if (title.isEmpty()) title = runsText(sh.optJSONObject("title"));

            JSONArray items = new JSONArray();
            Set<String> seen = new HashSet<>();
            List<JSONObject> two = collect(sh, "musicTwoRowItemRenderer", null);
            for (JSONObject it : two) {
                JSONObject o = itemFromTwoRow(it);
                if (o != null && seen.add(o.optString("kind") + o.optString("id"))) items.put(o);
            }
            for (JSONObject it : collect(sh, "musicResponsiveListItemRenderer", null)) {
                JSONObject o = songFromListItem(it, null);
                if (o == null) {
                    String bid = str(path(it, "navigationEndpoint", "browseEndpoint", "browseId"));
                    if (bid.startsWith("MPRE")) {
                        o = new JSONObject();
                        o.put("kind", "album");
                        o.put("id", bid);
                        o.put("title", runsText(flex(it, 0)));
                        fillArtistYear(o, flexRuns(it, 1));
                        o.put("cover", sq(bestThumb(it), 300));
                    }
                } else {
                    o.put("kind", "song");
                }
                if (o != null && seen.add(o.optString("kind") + o.optString("id"))) items.put(o);
            }
            if (items.length() == 0) continue;
            JSONObject s = new JSONObject();
            s.put("title", title);
            s.put("items", items);
            out.put(s);
            if (out.length() >= 12) break;
        }
        if (out.length() == 0) throw new IOException("no shelves");
        return out;
    }

    /* ════════════════════════════════════════════
       PARSING
    ════════════════════════════════════════════ */

    /** 곡 항목(musicResponsiveListItemRenderer) → 트랙. videoId 가 없으면 null (앨범/아티스트 항목 등). */
    private JSONObject songFromListItem(JSONObject it, JSONObject albumMeta) throws JSONException {
        String vid = str(path(it, "playlistItemData", "videoId"));
        if (vid.isEmpty()) {
            vid = str(path(it, "overlay", "musicItemThumbnailOverlayRenderer", "content",
                    "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId"));
        }
        if (vid.isEmpty()) {
            JSONArray r0 = runsArr(flex(it, 0));
            if (r0 != null && r0.length() > 0) {
                vid = str(path(r0.optJSONObject(0), "navigationEndpoint", "watchEndpoint", "videoId"));
            }
        }
        if (vid.isEmpty()) return null;

        JSONObject t = new JSONObject();
        t.put("id", vid);
        t.put("title", runsText(flex(it, 0)));
        fillTrackMeta(t, flexRuns(it, 1));

        // 앨범 페이지: 두 번째 열에 재생수만 있거나 비어 있음 → 아티스트는 호출자가 채운다.
        if (albumMeta != null && t.optString("channel").isEmpty()) {
            t.put("channel", albumMeta.optString("artist", ""));
        }
        if (t.optInt("dur", 0) == 0) {
            JSONArray fc = it.optJSONArray("fixedColumns");
            if (fc != null && fc.length() > 0) {
                JSONObject txt = (JSONObject) path(fc.optJSONObject(0),
                        "musicResponsiveListItemFixedColumnRenderer", "text");
                t.put("dur", parseDur(runsText(txt)));
            }
        }
        String cv = bestThumb(it);
        if (!cv.isEmpty()) {
            t.put("cover", sq(cv, 300));
            t.put("thumb", sq(cv, 300));
        }
        if (t.optString("title").isEmpty()) return null;
        return t;
    }

    /** twoRow(카루셀/그리드) 항목 → 곡/앨범/플레이리스트/아티스트 */
    private JSONObject itemFromTwoRow(JSONObject it) throws JSONException {
        String title = runsText(it.optJSONObject("title"));
        if (title.isEmpty()) return null;
        JSONObject nav = it.optJSONObject("navigationEndpoint");
        if (nav == null) return null;

        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("cover", sq(bestThumb(it), 300));

        List<JSONObject> runs = new ArrayList<>();
        addRuns(runs, it.optJSONObject("subtitle"));

        String vid = str(path(nav, "watchEndpoint", "videoId"));
        String bid = str(path(nav, "browseEndpoint", "browseId"));
        if (!vid.isEmpty()) {
            o.put("kind", "song");
            o.put("id", vid);
            fillTrackMeta(o, runs);
            o.put("channel", o.optString("channel", ""));
            if (o.optString("cover").isEmpty()) o.put("cover", sq("https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg", 300));
            return o;
        }
        if (bid.startsWith("MPRE")) {
            o.put("kind", "album");
            o.put("id", bid);
            fillArtistYear(o, runs);
            return o;
        }
        if (bid.startsWith("VL") || bid.startsWith("PL") || bid.startsWith("RD")) {
            o.put("kind", "playlist");
            o.put("id", bid.startsWith("VL") ? bid : "VL" + bid);
            o.put("artist", joinPlain(runs));
            return o;
        }
        if (bid.startsWith("UC")) {
            o.put("kind", "artist");
            o.put("id", bid);
            return o;
        }
        return null;
    }

    /** runs: [아티스트(UC), " • ", 앨범(MPREb), " • ", "3:45"] 형태에서 channel/artistId/album/albumId/dur 채움 */
    private void fillTrackMeta(JSONObject t, List<JSONObject> runs) throws JSONException {
        List<String> artists = new ArrayList<>();
        String firstPlain = "";
        for (JSONObject r : runs) {
            String tx = r.optString("text", "").trim();
            if (tx.isEmpty() || tx.equals("•") || tx.equals("&") || tx.equals(",")) continue;
            String bid = str(path(r, "navigationEndpoint", "browseEndpoint", "browseId"));
            if (bid.startsWith("MPRE")) {
                t.put("album", tx);
                t.put("albumId", bid);
            } else if (bid.startsWith("UC")) {
                artists.add(tx);
                if (t.optString("artistId").isEmpty()) t.put("artistId", bid);
            } else if (DUR_RX.matcher(tx).matches()) {
                t.put("dur", parseDur(tx));
            } else if (firstPlain.isEmpty() && !TYPE_WORDS.contains(tx.toLowerCase())
                    && !YEAR_RX.matcher(tx).matches() && !tx.matches("^[\\d.,]+\\s*\\S*$")) {
                firstPlain = tx;
            }
        }
        if (!artists.isEmpty()) t.put("channel", join(artists, ", "));
        else if (!firstPlain.isEmpty()) t.put("channel", firstPlain);
        else if (!t.has("channel")) t.put("channel", "");
    }

    /** runs 에서 아티스트/연도 추출 (앨범·플레이리스트 헤더, 앨범 카드용) */
    private void fillArtistYear(JSONObject o, List<JSONObject> runs) throws JSONException {
        List<String> artists = new ArrayList<>();
        String plain = "";
        for (JSONObject r : runs) {
            String tx = r.optString("text", "").trim();
            if (tx.isEmpty() || tx.equals("•") || tx.equals("&") || tx.equals(",")) continue;
            String bid = str(path(r, "navigationEndpoint", "browseEndpoint", "browseId"));
            if (bid.startsWith("UC")) {
                artists.add(tx);
                if (o.optString("artistId").isEmpty()) o.put("artistId", bid);
            } else if (YEAR_RX.matcher(tx).matches()) {
                o.put("year", tx);
            } else if (plain.isEmpty() && !TYPE_WORDS.contains(tx.toLowerCase())
                    && !tx.matches(".*\\d+\\s*(곡|songs?|tracks?|분|minutes?|min|시간|hours?).*")) {
                plain = tx;
            }
        }
        if (!artists.isEmpty()) o.put("artist", join(artists, ", "));
        else if (!o.has("artist")) o.put("artist", plain);
    }

    /* ════════════════════════════════════════════
       HTTP + CACHE
    ════════════════════════════════════════════ */

    private JSONObject base(String gl) throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB_REMIX");
        client.put("clientVersion", CLIENT_VERSION);
        client.put("hl", "ko");
        client.put("gl", gl);
        JSONObject ctx = new JSONObject();
        ctx.put("client", client);
        JSONObject body = new JSONObject();
        body.put("context", ctx);
        return body;
    }

    private JSONObject cachedPost(String endpoint, JSONObject body, String key, boolean fresh) throws Exception {
        String ck = endpoint + "|" + key;
        long now = System.currentTimeMillis();
        if (!fresh) {
            synchronized (cache) {
                Object[] e = cache.get(ck);
                if (e != null && now - (Long) e[0] < TTL_MS) return (JSONObject) e[1];
            }
        }
        JSONObject json = post(endpoint, body);
        synchronized (cache) {
            cache.put(ck, new Object[]{now, json});
        }
        return json;
    }

    private JSONObject post(String endpoint, JSONObject body) throws Exception {
        Request req = new Request.Builder()
                .url(BASE + endpoint + "?key=" + KEY + "&prettyPrint=false")
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .addHeader("X-YouTube-Client-Name", "67")
                .addHeader("X-YouTube-Client-Version", CLIENT_VERSION)
                .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                .addHeader("User-Agent", UA)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new IOException("HTTP " + resp.code());
            return new JSONObject(new String(resp.body().bytes(), StandardCharsets.UTF_8));
        }
    }

    /* ════════════════════════════════════════════
       JSON HELPERS
    ════════════════════════════════════════════ */

    /** 키로 재귀 탐색: key 를 가진 JSONObject 값을 문서 순서대로 수집(찾은 노드 내부로는 내려가지 않음). */
    private static List<JSONObject> collect(Object node, String key, Set<String> skipKeys) {
        List<JSONObject> out = new ArrayList<>();
        walk(node, key, skipKeys, out, 0);
        return out;
    }

    private static void walk(Object node, String key, Set<String> skip, List<JSONObject> out, int depth) {
        if (node == null || depth > 40) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject hit = o.optJSONObject(key);
            if (hit != null) out.add(hit);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (skip != null && skip.contains(k)) continue;
                if (k.equals(key)) continue;
                Object v = o.opt(k);
                if (v instanceof JSONObject || v instanceof JSONArray) walk(v, key, skip, out, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject || v instanceof JSONArray) walk(v, key, skip, out, depth + 1);
            }
        }
    }

    private static JSONObject firstOf(Object node, String... keys) {
        for (String k : keys) {
            List<JSONObject> l = collect(node, k, null);
            if (!l.isEmpty()) return l.get(0);
        }
        return null;
    }

    private static Object path(Object node, Object... keys) {
        Object cur = node;
        for (Object k : keys) {
            if (cur == null) return null;
            if (k instanceof String) {
                if (!(cur instanceof JSONObject)) return null;
                cur = ((JSONObject) cur).opt((String) k);
            } else {
                if (!(cur instanceof JSONArray)) return null;
                cur = ((JSONArray) cur).opt((Integer) k);
            }
        }
        return cur;
    }

    private static String str(Object o) {
        return (o instanceof String) ? (String) o : "";
    }

    private static JSONObject flex(JSONObject item, int col) {
        Object o = path(item, "flexColumns", col, "musicResponsiveListItemFlexColumnRenderer", "text");
        return (o instanceof JSONObject) ? (JSONObject) o : null;
    }

    private static List<JSONObject> flexRuns(JSONObject item, int col) {
        List<JSONObject> l = new ArrayList<>();
        addRuns(l, flex(item, col));
        return l;
    }

    private static JSONArray runsArr(JSONObject textObj) {
        return textObj == null ? null : textObj.optJSONArray("runs");
    }

    private static void addRuns(List<JSONObject> into, JSONObject textObj) {
        JSONArray a = runsArr(textObj);
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r != null) into.add(r);
        }
    }

    private static String runsText(JSONObject textObj) {
        if (textObj == null) return "";
        JSONArray a = textObj.optJSONArray("runs");
        if (a == null) return textObj.optString("simpleText", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString();
    }

    /** 노드 안의 첫 "thumbnails" 배열에서 가장 큰(마지막) URL */
    private static String bestThumb(Object node) {
        JSONArray best = firstThumbArray(node, 0);
        if (best == null || best.length() == 0) return "";
        JSONObject last = best.optJSONObject(best.length() - 1);
        return last == null ? "" : last.optString("url", "");
    }

    private static JSONArray firstThumbArray(Object node, int depth) {
        if (node == null || depth > 12) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray a = o.optJSONArray("thumbnails");
            if (a != null) return a;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object v = o.opt(it.next());
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    JSONArray r = firstThumbArray(v, depth + 1);
                    if (r != null) return r;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray r = firstThumbArray(arr.opt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 구글 이미지 CDN 은 =wN-hN 으로 크기를 지정할 수 있어 카드용 작은 정사각형으로 줄인다(디코드 메모리 절약). */
    static String sq(String url, int px) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("//")) url = "https:" + url;
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return SIZE_RX.matcher(url).replaceFirst("=w" + px + "-h" + px + "-l90-rj");
        }
        return url;
    }

    private static int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.trim().split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String joinPlain(List<JSONObject> runs) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject r : runs) sb.append(r.optString("text", ""));
        return sb.toString().trim();
    }
}
