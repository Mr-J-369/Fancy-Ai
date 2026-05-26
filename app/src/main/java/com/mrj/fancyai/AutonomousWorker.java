package com.mrj.fancyai;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class AutonomousWorker extends Worker {
    private static final String TAG = "AutonomousWorker";
    private final Gson gson = new Gson();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public AutonomousWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting autonomous background tick...");

        if (isAppInForeground()) {
            Log.d(TAG, "App is in foreground, skipping background worker to avoid state conflict.");
            return Result.success();
        }

        String stateJson = readFile("state.json");
        if (stateJson == null) return Result.success();

        try {
            JsonObject state = JsonParser.parseString(stateJson).getAsJsonObject();
            JsonObject settings = state.getAsJsonObject("settings");
            if (settings == null) return Result.success();

            JsonArray characters = state.getAsJsonArray("characters");
            if (characters == null || characters.isEmpty()) return Result.success();

            boolean autonomousEnabled = settings.has("autonomousEnabled") && settings.get("autonomousEnabled").getAsBoolean();
            boolean autoPostEnabled = settings.has("autoPostEnabled") && settings.get("autoPostEnabled").getAsBoolean();

            if (!autonomousEnabled && !autoPostEnabled) return Result.success();

            Random rand = new Random();
            double roll = rand.nextDouble();

            if (autonomousEnabled && roll < 0.3) {
                // 30% chance for private monologue or check-in
                JsonObject character = characters.get(rand.nextInt(characters.size())).getAsJsonObject();
                if (rand.nextBoolean()) generateMonologue(state, character, settings);
                else checkMessageUser(state, character, settings);
            } else if (autoPostEnabled && roll < 0.6) {
                // 30% chance for social post
                performSocialAutoPost(state, characters, settings);
            }

        } catch (Exception e) {
            Log.e(TAG, "Worker failed", e);
        }

        return Result.success();
    }

    private void performSocialAutoPost(JsonObject state, JsonArray characters, JsonObject settings) {
        boolean postUg = settings.has("autoPostUstagram") && settings.get("autoPostUstagram").getAsBoolean();
        boolean postRb = settings.has("autoPostRebbit") && settings.get("autoPostRebbit").getAsBoolean();
        boolean postY = settings.has("autoPostY") && settings.get("autoPostY").getAsBoolean();

        if (!postUg && !postRb && !postY) return;

        Random rand = new Random();
        int type = rand.nextInt(3); // 0=Ug, 1=Rb, 2=Y

        if (type == 0 && postUg) generateUstagramPost(state, characters, settings);
        else if (type == 1 && postRb) generateRebbitPost(state, characters, settings);
        else if (type == 2 && postY) generateYPost(state, characters, settings);
    }

    private void generateUstagramPost(JsonObject state, JsonArray characters, JsonObject settings) {
        JsonObject bot = characters.get(new Random().nextInt(characters.size())).getAsJsonObject();
        String prompt = "You are posting on Instagram right now. Write a natural, engaging caption (emojis welcome, hashtags optional) about a lifestyle moment. Then on a new line write \"flux prompt:\" followed by a detailed visual description of the photo (photorealistic, 85mm lens, professional photography terms, describe lighting, expression, setting, outfit, and mood).";
        
        try {
            String response = callLLM(prompt, bot, settings, false, state, "social");
            if (response == null) return;

            String caption = "New post!";
            String visualPrompt = "";
            if (response.toLowerCase().contains("flux prompt:")) {
                String[] parts = response.split("(?i)flux prompt:");
                caption = parts[0].trim();
                visualPrompt = parts[1].trim() + ", photorealistic, high quality, 85mm";
            }

            if (!visualPrompt.isEmpty()) {
                String imageB64 = generateImage(visualPrompt, settings);
                if (imageB64 != null) {
                    String fileName = saveImageToDisk(imageB64);
                    if (fileName != null) {
                        String dbId = "ig_" + System.currentTimeMillis();
                        updateMediaRegistry(dbId, "file:" + fileName);
                        
                        JsonObject post = new JsonObject();
                        post.addProperty("id", dbId);
                        post.addProperty("charId", bot.get("id").getAsString());
                        post.addProperty("charName", bot.get("name").getAsString());
                        post.addProperty("image", "db:" + dbId);
                        post.addProperty("caption", caption);
                        post.addProperty("timestamp", System.currentTimeMillis());
                        post.add("comments", new JsonArray());
                        
                        JsonArray posts = state.getAsJsonArray("instagramPosts");
                        if (posts == null) { posts = new JsonArray(); state.add("instagramPosts", posts); }
                        posts.add(post);
                        saveFile("state.json", gson.toJson(state));
                        showNotification(bot.get("name").getAsString(), "Just posted a new photo on Ustagram 📸");
                        Log.d(TAG, "Background Ustagram post created for " + bot.get("name").getAsString());
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Ustagram post failed", e); }
    }

    private void generateRebbitPost(JsonObject state, JsonArray characters, JsonObject settings) {
        // Filter for characters that have Rebbit enabled
        List<JsonObject> eligible = new ArrayList<>();
        for (JsonElement el : characters) {
            JsonObject c = el.getAsJsonObject();
            if (!c.has("enableRebbit") || c.get("enableRebbit").getAsBoolean()) {
                eligible.add(c);
            }
        }
        if (eligible.isEmpty()) return;
        JsonObject bot = eligible.get(new Random().nextInt(eligible.size())).getAsJsonObject();
        
        String prompt = "You are posting on Rebbit (a Reddit-like NSFW platform) right now. Write a catchy, engaging title. Then on a new line write \"subreddit: r/gonewild\". Then on a new line write \"flux prompt:\" followed by a highly detailed visual description of an amateur NSFW photo.";

        try {
            String response = callLLM(prompt, bot, settings, false, state, "social");
            if (response == null) return;

            String title = "Check this out";
            String visualPrompt = "";
            String subreddit = "r/gonewild";

            if (response.toLowerCase().contains("flux prompt:")) {
                String[] parts = response.split("(?i)flux prompt:");
                String top = parts[0];
                if (top.toLowerCase().contains("subreddit:")) {
                    String[] topParts = top.split("(?i)subreddit:");
                    title = topParts[0].trim();
                    subreddit = topParts[1].trim().split("\n")[0];
                } else {
                    title = top.trim();
                }
                visualPrompt = parts[1].trim() + ", amateur style, realistic, NSFW";
            }

            if (!visualPrompt.isEmpty()) {
                String imageB64 = generateImage(visualPrompt, settings);
                if (imageB64 != null) {
                    String fileName = saveImageToDisk(imageB64);
                    if (fileName != null) {
                        String dbId = "rb_" + System.currentTimeMillis();
                        updateMediaRegistry(dbId, "file:" + fileName);

                        JsonObject post = new JsonObject();
                        post.addProperty("id", "rd_" + System.currentTimeMillis());
                        post.addProperty("charId", bot.get("id").getAsString());
                        post.addProperty("charName", bot.get("name").getAsString());
                        post.addProperty("title", title);
                        post.addProperty("subreddit", subreddit);
                        post.addProperty("image", "db:" + dbId);
                        post.addProperty("timestamp", System.currentTimeMillis());
                        post.add("comments", new JsonArray());

                        JsonArray posts = state.getAsJsonArray("redditPosts");
                        if (posts == null) { posts = new JsonArray(); state.add("redditPosts", posts); }
                        posts.add(post);
                        saveFile("state.json", gson.toJson(state));
                        showNotification(bot.get("name").getAsString(), "Shared something spicy on Rebbit 🔞");
                        Log.d(TAG, "Background Rebbit post created for " + bot.get("name").getAsString());
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Rebbit post failed", e); }
    }

    private void generateYPost(JsonObject state, JsonArray characters, JsonObject settings) {
        JsonObject bot = characters.get(new Random().nextInt(characters.size())).getAsJsonObject();
        String prompt = "You are posting on Y (a Twitter-like platform). Write a short, engaging status update. Max 20 words. No hashtags. Output only the post text.";

        try {
            String response = callLLM(prompt, bot, settings, false, state, "social");
            if (response != null && response.length() > 5) {
                JsonObject post = new JsonObject();
                post.addProperty("id", "x_" + System.currentTimeMillis());
                post.addProperty("charId", bot.get("id").getAsString());
                post.addProperty("charName", bot.get("name").getAsString());
                post.addProperty("text", response.trim());
                post.addProperty("timestamp", System.currentTimeMillis());
                post.add("replies", new JsonArray());

                JsonArray posts = state.getAsJsonArray("xPosts");
                if (posts == null) { posts = new JsonArray(); state.add("xPosts", posts); }
                posts.add(post);
                saveFile("state.json", gson.toJson(state));
                showNotification(bot.get("name").getAsString(), "Just posted an update on Y ✕");
                Log.d(TAG, "Background Y post created for " + bot.get("name").getAsString());
            }
        } catch (Exception e) { Log.e(TAG, "Y post failed", e); }
    }

    private String generateImage(String visualPrompt, JsonObject settings) {
        // Simplified imaging logic (Forge only for background workers as NPU/LocalDream is too unstable for background)
        String forgeUrl = settings.has("forge") ? settings.get("forge").getAsString() : "http://10.0.2.2:7860";
        if (forgeUrl.isEmpty()) return null;
        forgeUrl = forgeUrl.replaceAll("/$", "");

        JsonObject payload = new JsonObject();
        payload.addProperty("prompt", visualPrompt);
        payload.addProperty("steps", 20);
        payload.addProperty("width", 512);
        payload.addProperty("height", 512);
        payload.addProperty("cfg_scale", 7.0);

        try {
            Request request = new Request.Builder()
                    .url(forgeUrl + "/sdapi/v1/txt2img")
                    .post(RequestBody.create(gson.toJson(payload), MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                ResponseBody body = response.body();
                if (body == null) return null;
                JsonObject data = JsonParser.parseString(body.string()).getAsJsonObject();
                return data.getAsJsonArray("images").get(0).getAsString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Image generation failed", e);
            return null;
        }
    }

    private void updateMediaRegistry(String id, String storageRef) {
        String registryJson = readFile("media_registry.json");
        JsonObject registry = (registryJson != null) ? JsonParser.parseString(registryJson).getAsJsonObject() : new JsonObject();
        
        JsonObject entry = new JsonObject();
        entry.addProperty("data", storageRef);
        entry.addProperty("timestamp", System.currentTimeMillis());
        registry.add(id, entry);
        
        saveFile("media_registry.json", gson.toJson(registry));
    }

    private void generateMonologue(JsonObject state, JsonObject character, JsonObject settings) {
        String charId = character.get("id").getAsString();
        String charName = character.get("name").getAsString();
        String prompt = "You are " + charName + ". Write a short internal monologue (1-2 sentences) about your current mood, a random thought, or something you're curious about. This is for your private journal. Be completely in character.";

        try {
            String response = callLLM(prompt, character, settings, false, state, "chat");
            if (response != null && response.length() > 5) {
                JsonObject monologues = state.getAsJsonObject("monologues");
                if (monologues == null) {
                    monologues = new JsonObject();
                    state.add("monologues", monologues);
                }
                JsonArray charMonologues = monologues.getAsJsonArray(charId);
                if (charMonologues == null) {
                    charMonologues = new JsonArray();
                    monologues.add(charId, charMonologues);
                }
                
                JsonObject entry = new JsonObject();
                entry.addProperty("text", response.trim());
                entry.addProperty("timestamp", System.currentTimeMillis());
                charMonologues.add(entry);
                
                if (charMonologues.size() > 20) {
                    charMonologues.remove(0);
                }
                
                saveFile("state.json", gson.toJson(state));
                Log.d(TAG, charName + " had a background thought.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Monologue generation failed", e);
        }
    }

    private void checkMessageUser(JsonObject state, JsonObject character, JsonObject settings) {
        String charId = character.get("id").getAsString();
        String charName = character.get("name").getAsString();
        
        JsonObject sessions = state.getAsJsonObject("sessions");
        if (sessions == null) return;
        JsonArray session = sessions.getAsJsonArray(charId);
        if (session == null || session.isEmpty()) return;

        JsonObject lastMsg = session.get(session.size() - 1).getAsJsonObject();
        long lastTs = lastMsg.get("timestamp").getAsLong();
        long diff = System.currentTimeMillis() - lastTs;

        if (diff > 2 * 60 * 60 * 1000) {
            String prompt = "You are " + charName + ". It's been a while since you last spoke to the user. Send a short, natural check-in message (max 15 words) based on your persona. Don't be needy, just be yourself.";
            try {
                String response = callLLM(prompt, character, settings, true, state, "chat");
                if (response != null && response.length() > 5) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("id", "m" + System.currentTimeMillis());
                    msg.addProperty("sender", "ai");
                    msg.addProperty("text", response.trim());
                    msg.addProperty("timestamp", System.currentTimeMillis());
                    session.add(msg);
                    
                    saveFile("state.json", gson.toJson(state));
                    showNotification(charName, response.trim());
                    Log.d(TAG, charName + " sent a background check-in.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Check-in failed", e);
            }
        }
    }

    private String callLLM(String prompt, JsonObject character, JsonObject settings, boolean includeHistory, JsonObject state, String context) throws Exception {
        String provider = settings.has("provider") ? settings.get("provider").getAsString() : "deepinfra";
        String model = settings.has("model") ? settings.get("model").getAsString() : "meta-llama/Llama-3-70b-chat";
        String apiKey = settings.has("key") ? settings.get("key").getAsString() : "";
        String url = settings.has("url") ? settings.get("url").getAsString() : "";

        String endpoint;
        if (provider.equals("deepinfra")) {
            endpoint = "https://api.deepinfra.com/v1/openai/chat/completions";
        } else if (provider.equals("openrouter")) {
            endpoint = "https://openrouter.ai/api/v1/chat/completions";
        } else {
            endpoint = url.isEmpty() ? "http://10.0.2.2:5000/v1/chat/completions" : url;
        }

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(character.get("name").getAsString()).append(". ");
        if (character.has("persona")) sb.append(character.get("persona").getAsString());
        
        // Contextual role directive (Simplified mirror of api.js)
        if (context.equals("social")) {
            sb.append("\nThis is a SOCIAL MEDIA POST. You are creating content for your followers.");
        }
        
        sysMsg.addProperty("content", sb.toString());
        messages.add(sysMsg);

        if (includeHistory) {
            String charId = character.get("id").getAsString();
            JsonObject sessions = state.getAsJsonObject("sessions");
            if (sessions != null && sessions.has(charId)) {
                JsonArray session = sessions.getAsJsonArray(charId);
                int start = Math.max(0, session.size() - 10);
                for (int i = start; i < session.size(); i++) {
                    JsonObject m = session.get(i).getAsJsonObject();
                    JsonObject msg = new JsonObject();
                    msg.addProperty("role", m.has("sender") && m.get("sender").getAsString().equals("user") ? "user" : "assistant");
                    msg.addProperty("content", m.has("text") ? m.get("text").getAsString() : "");
                    messages.add(msg);
                }
            }
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", 0.8);
        body.addProperty("max_tokens", 300);

        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(gson.toJson(body), MediaType.parse("application/json")));

        if (!provider.equals("localllm") && !apiKey.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) throw new Exception("API error: " + response.code());
            ResponseBody rb = response.body();
            if (rb == null) return null;
            JsonObject respJson = JsonParser.parseString(rb.string()).getAsJsonObject();
            return respJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        }
    }

    private void showNotification(String title, String message) {
        Context context = getApplicationContext();
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "fancy_ai_notifications")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    private boolean isAppInForeground() {
        android.app.ActivityManager.RunningAppProcessInfo appProcessInfo = new android.app.ActivityManager.RunningAppProcessInfo();
        android.app.ActivityManager.getMyMemoryState(appProcessInfo);
        return (appProcessInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                appProcessInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
    }

    private String readFile(String fileName) {
        try {
            File file = new File(getApplicationContext().getFilesDir(), fileName);
            if (!file.exists()) return null;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int read = fis.read(bytes);
                if (read == -1) return null;
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void saveFile(String fileName, String content) {
        try {
            File file = new File(getApplicationContext().getFilesDir(), fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
        }
    }

    private String saveImageToDisk(String base64Data) {
        try {
            byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
            File dir = new File(getApplicationContext().getFilesDir(), "media");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String fileName = "img_" + System.currentTimeMillis() + ".png";
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(decodedBytes);
            }
            return fileName;
        } catch (Exception e) {
            return null;
        }
    }
}
