package com.retzero.forest.auto;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AutoForest {

    private static ThreadLocal<String> miniwua = new ThreadLocal<>();

    //the user cookie
    private static ThreadLocal<String> cookie = new ThreadLocal<>();

    private static ThreadLocal<Integer> totalEnergy = ThreadLocal.withInitial(() -> 0);

    private static ThreadLocal<Integer> totalForFriendEnergy = ThreadLocal.withInitial(() -> 0);

    private static ThreadLocal<Integer> allTotalEnergy = ThreadLocal.withInitial(() -> 0);

    private static ThreadLocal<Integer> allTotalForFriendEnergy = ThreadLocal.withInitial(() -> 0);

    private final static SignSo signso = new SignSo();

    @Value("${path.account}")
    private String configPath;
    @Value("${path.apk}")
    private String apkPath;
    @Value("${path.so}")
    private String soPath;

    private void disableSystemErr() {
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
            }
        }));
    }

    public void start() {
        disableSystemErr();
        signso.initSign(apkPath, soPath);
        work();
    }

    @PreDestroy
    public void destroy() throws IOException {
        signso.destroy();
    }

    public List<Personal> readConfig() throws IOException {
        /* 读入TXT文件 */
        List<Personal> personals = new ArrayList<>();
        File filename = new File(configPath);
        InputStreamReader reader;
        try {
            reader = new InputStreamReader(
                    new FileInputStream(filename));
        } catch (FileNotFoundException e) {
            return personals;
        }
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            String account = line.trim();
            if (account.length() > 0) {
                String[] info = line.split("---");
                Personal p1 = new Personal(info[0],
                        "ALIPAYJSESSIONID=" + info[1],
                        "{\"w\":\"" + info[2] + "\"}");
                personals.add(p1);
            }
        }
        br.close();
        reader.close();
        return personals;
    }

    private void work() {
        while (true) {
            List<Personal> personals = Collections.emptyList();
            try {
                personals = readConfig();
            } catch (Exception e) {
                log.error("Read Config Error", e);
            }
            for (Personal personal : personals) {
                if (personal == null) continue;
                allTotalEnergy.set(personal.getAllTotalEnergy());
                allTotalForFriendEnergy.set(personal.getAllTotalForFriendEnergy());
                cookie.set(personal.getCookie());
                miniwua.set(personal.getMiniwua());

                log.info("{} : 收取能量开始", personal.getName());

                try {
                    autoTookFriend();
                } catch (Exception e) {
                    log.error("Took Error", e);
                }
                personal.setAllTotalEnergy(allTotalEnergy.get());
                personal.setAllTotalForFriendEnergy(allTotalForFriendEnergy.get());
            }

            int time = (int) (60 + Math.random() * (90 - 60 + 1));
            log.info("Sleep: {}s", time);
            try {
                Thread.sleep(time * 1000);
            } catch (InterruptedException e) {
                log.error("InterruptedException", e);
            }
        }
    }

    private static void autoTookFriend() throws JSONException {

        //开始解析好友信息，循环把所有有能量的好友信息都解析完

        boolean success = true;

        JSONObject response;

        int i = 0;
        while (success) {

            totalEnergy.set(0);
            totalForFriendEnergy.set(0);
            response = searchEnergy();
            String friendId = response.optString("friendId", "");
            if (Strings.isBlank(friendId)) {
                log.info("没有好友可以收取！");
                success = false;
                continue;
            }
            response = queryFriendHomePage(friendId);
            tookEnergyFriend(response);
            i++;
            if (i > 200) {
                log.error("出现问题, 过度循环！");
                // 防止出现问题 过度循环
                success = false;
            }
            if (totalEnergy.get() > 0) log.info("本次收取了{}g能量", totalEnergy.get());
            if (totalForFriendEnergy.get() > 0) log.info("本次帮好友收取了{}g能量", totalForFriendEnergy.get());
            allTotalEnergy.set(allTotalEnergy.get() + totalEnergy.get());
            allTotalForFriendEnergy.set(allTotalForFriendEnergy.get() + totalForFriendEnergy.get());
        }

        JSONObject own = queryFriendHomePage(null);
        tookEnergyFriend(own);

        if (allTotalEnergy.get() > 0) log.info("一共收取了{}g能量", allTotalEnergy.get());
        if (allTotalForFriendEnergy.get() > 0) log.info("一共帮好友收取了{}g能量", allTotalForFriendEnergy.get());
        log.info("工作完毕");
    }

    @AllArgsConstructor
    public static class Energy {
        private Long id;
        private String targetUser;
    }

    private static List<Energy> helpFriendList(JSONArray bubbles) throws JSONException {
        List<Energy> res = new ArrayList<>();
        if (bubbles != null && bubbles.length() > 0) {
            for (int i = 0; i < bubbles.length(); i++) {
                JSONObject bubbleItem = bubbles.getJSONObject(i);
                res.add(new Energy(bubbleItem.optLong("id"), bubbleItem.optString("userId")));
            }
        }
        return res;
    }

    private static List<Energy> tookFriendList(JSONArray bubbles) throws JSONException {
        List<Energy> res = new ArrayList<>();
        if (bubbles != null && bubbles.length() > 0) {
            for (int i = 0; i < bubbles.length(); i++) {
                JSONObject bubbleItem = bubbles.getJSONObject(i);
                if ("AVAILABLE".equals(bubbleItem.optString("collectStatus"))) {
                    res.add(new Energy(bubbleItem.optLong("id"), bubbleItem.optString("userId")));
                }
            }
        }
        return res;
    }

    private static void tookEnergyFriend(JSONObject response) throws JSONException {
        JSONArray usingUserProps = response.optJSONArray("usingUserProps");

        //说明有保护罩
        if (usingUserProps.length() != 0 && response.optString("nextAction").contains("Friend")) return;

        JSONArray bubbles = response.optJSONArray("bubbles");
        JSONArray unrobbableBubbles = response.optJSONArray("unrobbableBubbles");
        JSONArray wateringBubbles = response.optJSONArray("wateringBubbles");
        List<Energy> tookAble = tookFriendList(bubbles);
        List<Energy> protectAble = helpFriendList(unrobbableBubbles);
        List<Energy> helpAble = helpFriendList(wateringBubbles);
        parseCollectEnergyResponse(collectEnergy(tookAble));
        parseForFriendEnergyResponse(forFriendCollectEnergy(helpAble, false));
        parseForFriendEnergyResponse(forFriendCollectEnergy(protectAble, true));
    }

    private static JSONObject queryFriendHomePage(String userId) throws JSONException {
        final String body;
        if (userId !=null) {
            body = "[{\"canRobFlags\":\"F,F,F,F,F\",\"configVersionMap\":{\"redPacketConfig\":20200702,\"wateringBubbleConfig\":\"10\"},\"source\":\"_NO_SOURCE_\",\"userId\":\"" + userId + "\",\"version\":\"20181220\"}]";
        } else {
            body = "[{\"canRobFlags\":\"F,F,F,F,F\",\"configVersionMap\":{\"redPacketConfig\":20200702,\"wateringBubbleConfig\":\"10\"},\"source\":\"_NO_SOURCE_\",\"version\":\"20181220\"}]";
        }

        return new JSONObject(executeHttp("alipay.antforest.forest.h5.queryFriendHomePage", body));
    }

    /**
     * 找能量
     *
     * @return Response
     */
    private static JSONObject searchEnergy() throws JSONException {
        return new JSONObject(executeHttp("alipay.antforest.forest.h5.takeLook", "[{\"excludeUserIds\":[],\"source\":\"_NO_SOURCE_\"}]"));
    }

    //偷取好友能量
    private static JSONObject collectEnergy(List<Energy> tookAble) {

        if (tookAble == null || tookAble.isEmpty()) {
            return null;
        }

        String userId = tookAble.get(0).targetUser;
        List<Long> bubbleIds = tookAble.stream().map(item -> item.id).collect(Collectors.toList());
        try {

            JSONArray jsonArray = new JSONArray();
            JSONArray bubbleAry = new JSONArray();
            bubbleIds.forEach(bubbleAry::put);
            JSONObject json = new JSONObject();
            json.put("bubbleIds", bubbleAry);
            json.put("userId", userId);
            jsonArray.put(json);
            return new JSONObject(executeHttp("alipay.antmember.forest.h5.collectEnergy", jsonArray.toString()));
        } catch (Exception e) {
            log.error("collectEnergy Error", e);
        }
        return null;
    }

    //偷好友能量返回解析
    public static void parseCollectEnergyResponse(JSONObject jsonObject) throws JSONException {
        if (jsonObject == null) {
            return;
        }

        if (!"SUCCESS".equals(jsonObject.optString("resultCode"))) return;

        JSONArray jsonArray = jsonObject.optJSONArray("bubbles");

        for (int i = 0; i < jsonArray.length(); i++) {
            totalEnergy.set(totalEnergy.get() + jsonArray.getJSONObject(i).optInt("collectedEnergy"));
        }
    }

    //帮好友收能量
    private static JSONObject forFriendCollectEnergy(List<Energy> helpAble, boolean isProtect) throws JSONException {
        if (helpAble == null || helpAble.isEmpty()) {
            return null;
        }

        if (isProtect) {
            for (Energy energy : helpAble) {
                JSONArray jsonArray = new JSONArray();
                JSONObject json = new JSONObject();
                json.put("bubbleId", energy.id);
                json.put("targetUserId", energy.targetUser);
                jsonArray.put(json);
                return new JSONObject(executeHttp("alipay.antforest.forest.h5.protectBubble", jsonArray.toString()));
            }
            return null;
        } else {
            String userId = helpAble.get(0).targetUser;
            List<Long> bubbleIds = helpAble.stream().map(item -> item.id).collect(Collectors.toList());

            try {
                JSONArray jsonArray = new JSONArray();
                JSONArray bubbleAry = new JSONArray();
                bubbleIds.forEach(bubbleAry::put);
                JSONObject json = new JSONObject();
                json.put("bubbleIds", bubbleAry);
                json.put("targetUserId", userId);
                jsonArray.put(json);
                return new JSONObject(executeHttp("alipay.antmember.forest.h5.forFriendCollectEnergy", jsonArray.toString()));
            } catch (Exception e) {
                log.error("forFriendCollectEnergy Error", e);
            }
        }

        return null;
    }

    //帮好友收能量返回解析
    public static void parseForFriendEnergyResponse(JSONObject jsonObject) throws JSONException {
        if (jsonObject == null) {
            return;
        }

        if (!"SUCCESS".equals(jsonObject.optString("resultCode"))) return;
        JSONArray jsonArray = jsonObject.optJSONArray("bubbles");
        for (int i = 0; i < jsonArray.length(); i++) {
            totalForFriendEnergy.set(totalForFriendEnergy.get() + jsonArray.getJSONObject(i).optInt("collectedEnergy"));
        }
    }

    //执行HTTP POST操作
    private static String executeHttp(String operationType, String postData) {
        String TAG = operationType.replaceAll("alipay.antforest.forest.h5.", "");
        TAG = TAG.replaceAll("alipay.antmember.forest.h5.", "");
        log.debug("{}_SEND: {}", TAG, postData);
        StringBuilder response = new StringBuilder();

        try {

            String ts = get64Time();
            String sign = getSign(operationType, postData, ts);
            URL url = new URL("https://mobilegw.alipay.com/mgw.htm");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");

            conn.setRequestProperty("visibleflag", "1");
            conn.setRequestProperty("AppId", "Android-container");
            conn.setRequestProperty("Version", "2");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("clientVersion", "10.1.75.7000");
            conn.setRequestProperty("Accept-Language", "zh-Hans");
            conn.setRequestProperty("Retryable2", "0");

            conn.setRequestProperty("miniwua", miniwua.get());
            //the version of product
            String x_mgs_productversion = "8f506f2969f782edf058bac736d055d55f7cb0828d8bd64aca2c1857b7332a6c11443809811f528dc54bdf28d5da6d614d8571066f80c18aca4fe86a05acdc790444da0ea5c7d347dd282640f0a10b56";
            conn.setRequestProperty("x-mgs-productversion", x_mgs_productversion);
            //Did is a deviceId
            String did = "X+nDp3UE4OYDAD3WR800FurZ";
            conn.setRequestProperty("Did", did);
            conn.setRequestProperty("Operation-Type", operationType);
            conn.setRequestProperty("Ts", ts);
            conn.setRequestProperty("Sign", sign);
            conn.setRequestProperty("Cookie", cookie.get());

            conn.setDoOutput(true);
            conn.setDoInput(true);

            conn.connect();

            DataOutputStream dataout = new DataOutputStream(conn.getOutputStream());
            dataout.writeBytes(postData);
            dataout.flush();
            dataout.close();

            BufferedReader buffer = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));

            String line;

            while ((line = buffer.readLine()) != null) {
                response.append(line);
            }

            if (response.toString().equals("")) {
                Map<String, List<String>> Properties = conn.getHeaderFields();
                log.error("Http Error: {}", URLDecoder.decode(Properties.get("Tips").toString(), "UTF-8"));
            }

            buffer.close();
            conn.disconnect();

        } catch (Exception e) {
            log.error("Http Error", e);
        }

        log.debug("{}_RECV: {}", TAG, response.toString());
        return response.toString();
    }

    //获取签名值
    private static String getSign(String operationType, String postData, String ts) {

        Base64.Encoder encoder = Base64.getEncoder();

        String signData = "Operation-Type=" + operationType +
                "&Request-Data=" +
                encoder.encodeToString(postData.getBytes()) +
                "&Ts=" +
                ts;

        return signso.doCommandNative(signData);
    }

    //获取64进制的时间戳
    private static String get64Time() {
        long ts = System.currentTimeMillis();
        return c10to64(ts);
    }

    //特殊的十进制到64进制
    private static String c10to64(long j) {
        char[] a = new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '+', '/'};
        int pow = (int) Math.pow(2.0d, 6.0d);
        char[] cArr = new char[pow];
        int i = pow;
        do {
            i--;
            cArr[i] = a[(int) (63 & j)];
            j >>>= 6;
        } while (j != 0);
        return new String(cArr, i, pow - i);
    }

}
