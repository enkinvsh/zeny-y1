package com.themoon.y1.managers;

import com.themoon.y1.Y1Paths;

import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.widget.Toast;
import com.themoon.y1.MainActivity;
import java.io.File;

public class AudioEffectManager {
    private static AudioEffectManager instance;

    private AudioEffectManager() {}

    public static synchronized AudioEffectManager getInstance() {
        if (instance == null) {
            instance = new AudioEffectManager();
        }
        return instance;
    }

    public void ensureAudioEffectsReady() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            int sessionId = com.themoon.y1.managers.AudioPlayerManager.getInstance().getAudioSessionId();
            if (main.equalizer == null || main.currentAudioSessionId != sessionId) {
                if (main.equalizer != null) main.equalizer.release();
                main.equalizer = new Equalizer(0, sessionId);
            }
            if (main.bassBoost == null || main.currentAudioSessionId != sessionId) {
                if (main.bassBoost != null) main.bassBoost.release();
                main.bassBoost = new android.media.audiofx.BassBoost(0, sessionId);
            }
            if (main.virtualizer == null || main.currentAudioSessionId != sessionId) {
                if (main.virtualizer != null) main.virtualizer.release();
                main.virtualizer = new android.media.audiofx.Virtualizer(0, sessionId);
            }
            if (android.os.Build.VERSION.SDK_INT >= 19
                    && (main.loudnessEnhancer == null || main.currentAudioSessionId != sessionId)) {
                if (main.loudnessEnhancer != null) main.loudnessEnhancer.release();
                main.loudnessEnhancer = new android.media.audiofx.LoudnessEnhancer(sessionId);
            }
            main.currentAudioSessionId = sessionId;
        } catch (Exception e) {}
    }

    public void applyAudioEffects() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        ensureAudioEffectsReady();

        ZenyAudioProcessor zeny = com.themoon.y1.managers.AudioPlayerManager.getInstance().zenyProcessor;
        zeny.setZenyNaturalEnabled(main.isZenyNaturalEnabled);
        zeny.setCrossfeedStep(main.currentCrossfeedStep);
        zeny.setSoundCheckEnabled(main.isSoundCheckEnabled);
        com.themoon.y1.managers.AudioPlayerManager.getInstance().crossfeedProcessor.setIntensity(main.currentCrossfeedStep);

        if (main.bassBoost != null) {
            try {
                // В Android 4.2 включённый стоковый эффект гоняет поток через 16-битный микшер MediaTek даже при силе 0
                if (main.currentBassBoostStep > 0) {
                    short bassStrength = (short) (main.currentBassBoostStep * 333);
                    if (main.currentBassBoostStep == 3) bassStrength = 1000;
                    main.bassBoost.setEnabled(true);
                    if (main.bassBoost.getStrengthSupported()) main.bassBoost.setStrength(bassStrength);
                } else {
                    main.bassBoost.setEnabled(false);
                }
            } catch (Exception e) {}
        }
        if (main.virtualizer != null) {
            try {
                if (main.currentVirtualizerStep > 0) {
                    short virtStrength = (short) (main.currentVirtualizerStep * 333);
                    if (main.currentVirtualizerStep == 3) virtStrength = 1000;
                    main.virtualizer.setEnabled(true);
                    if (main.virtualizer.getStrengthSupported()) main.virtualizer.setStrength(virtStrength);
                } else {
                    main.virtualizer.setEnabled(false);
                }
            } catch (Exception e) {}
        }

        applySoundCheck();
    }

    public void applySoundCheck() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        com.themoon.y1.managers.AudioPlayerManager.getInstance().zenyProcessor.setSoundCheckEnabled(main.isSoundCheckEnabled);
        if (android.os.Build.VERSION.SDK_INT < 19 || main.loudnessEnhancer == null) return;
        try {
            main.loudnessEnhancer.setEnabled(false);
        } catch (Exception e) {}
    }

    public void applyEqProfile() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        ensureAudioEffectsReady();

        ZenyAudioProcessor zeny = com.themoon.y1.managers.AudioPlayerManager.getInstance().zenyProcessor;
        zeny.setZenyNaturalEnabled(main.isZenyNaturalEnabled);
        try {
            if (main.isSoftwareEqEnabled) {
                if (main.equalizer != null) main.equalizer.setEnabled(false);
                com.themoon.y1.managers.AudioPlayerManager.getInstance().customEqProcessor.setEnabled(true);
                zeny.setSoftwareEqEnabled(true);

                String name = main.currentEqProfile.replace("custom_", "");
                if (main.currentEqProfile.startsWith("preset_")) name = "Flat";

                for (short i = 0; i < 10; i++) {
                    int level = main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0);
                    main.customBandLevels[i] = level;
                    float db = level / 100.0f;
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().customEqProcessor.setBandLevel(i, db);
                    zeny.setBandLevel(i, db);
                }
                main.prefs.edit().putString("eq_profile_id", main.currentEqProfile).commit();
            } else {
                com.themoon.y1.managers.AudioPlayerManager.getInstance().customEqProcessor.setEnabled(false);
                zeny.setSoftwareEqEnabled(false);
                if (main.equalizer == null) return;

                if (main.currentEqProfile.startsWith("preset_")) {
                    int pIdx = Integer.parseInt(main.currentEqProfile.replace("preset_", ""));
                    if (pIdx < main.equalizer.getNumberOfPresets()) {
                        main.currentEqPresetIndex = pIdx;
                        // Пресет 0 (Normal) оставляем выключенным на уровне AudioFlinger, чтобы обходить стоковый EffectChain
                        if (pIdx == 0) {
                            main.equalizer.setEnabled(false);
                        } else {
                            main.equalizer.setEnabled(true);
                            main.equalizer.usePreset((short) pIdx);
                        }
                        main.prefs.edit().putInt("eq_preset", main.currentEqPresetIndex).putString("eq_profile_id", main.currentEqProfile).commit();
                    }
                } else {
                    String name = main.currentEqProfile.replace("custom_", "");
                    short bands = main.equalizer.getNumberOfBands();
                    boolean anyNonZero = false;
                    for (short i = 0; i < bands; i++) {
                        int level = main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0);
                        main.customBandLevels[i] = level;
                        if (level != 0) anyNonZero = true;
                    }
                    if (anyNonZero) {
                        main.equalizer.setEnabled(true);
                        for (short i = 0; i < bands; i++) {
                            main.equalizer.setBandLevel(i, (short) main.customBandLevels[i]);
                        }
                    } else {
                        main.equalizer.setEnabled(false);
                    }
                    main.prefs.edit().putString("eq_profile_id", main.currentEqProfile).commit();
                }
            }
        } catch (Exception e) {}
    }

    // 🎧 4. 로컬 공유 메모리 저장
    public void saveCustomEqProfile(String name) {
        MainActivity main = MainActivity.instance;
        if (main == null || main.equalizer == null) return;
        short bands = main.equalizer.getNumberOfBands();
        SharedPreferences.Editor editor = main.prefs.edit();
        for (short i = 0; i < bands; i++) {
            editor.putInt("eq_custom_" + name + "_band_" + i, main.customBandLevels[i]);
        }
        editor.commit();
    }

    // 🎧 5. 내부 금고 및 외부 JSON 백업 물리 소멸 삭제
    public void deleteCustomEqProfile(String name) {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            File file = new File((Y1Paths.SD + "/Y1_EQs/") + name + ".json");
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        String listStr = main.prefs.getString("custom_eq_list", "");
        String[] items = listStr.split(",");
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (!item.equals(name) && !item.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(item);
            }
        }

        SharedPreferences.Editor editor = main.prefs.edit();
        editor.putString("custom_eq_list", sb.toString());

        for (int i = 0; i < 32; i++) {
            editor.remove("eq_custom_" + name + "_band_" + i);
        }
        editor.commit();

        com.themoon.y1.Y1Toast.makeText(main, "Profile Deleted Completely.", Toast.LENGTH_SHORT).show();
    }

    // 🎧 6. [외부 공유용] JSON 배출 엔진
    public void exportEqProfileToFile(String name) {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            File folder = new File((Y1Paths.SD + "/Y1_EQs"));
            if (!folder.exists()) folder.mkdirs();
            File file = new File(folder, name + ".json");

            org.json.JSONObject json = new org.json.JSONObject();
            json.put("profile_name", name);

            org.json.JSONArray bandsArray = new org.json.JSONArray();
            short bands = (main.equalizer != null) ? main.equalizer.getNumberOfBands() : 5;
            for (short i = 0; i < bands; i++) {
                bandsArray.put(main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0));
            }
            json.put("bands", bandsArray);

            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(json.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 🎧 7. [양방향 동기화] 폴더 스캔 및 자동 주입 구조
    public void loadAndSyncExternalEqProfiles() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        try {
            File folder = new File((Y1Paths.SD + "/Y1_EQs"));
            if (!folder.exists()) { folder.mkdirs(); return; }
            File[] files = folder.listFiles();

            java.util.ArrayList<String> validCustomList = new java.util.ArrayList<>();

            if (files != null) {
                SharedPreferences.Editor editor = main.prefs.edit();
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".json")) {
                        try {
                            java.io.FileInputStream fis = new java.io.FileInputStream(f);
                            byte[] data = new byte[(int) f.length()];
                            fis.read(data);
                            fis.close();

                            org.json.JSONObject json = new org.json.JSONObject(new String(data, "UTF-8"));
                            String name = json.optString("profile_name", f.getName().replace(".json", ""));
                            org.json.JSONArray bandsArray = json.optJSONArray("bands");

                            if (bandsArray != null) {
                                for (int i = 0; i < bandsArray.length(); i++) {
                                    editor.putInt("eq_custom_" + name + "_band_" + i, bandsArray.getInt(i));
                                }
                                if (!validCustomList.contains(name)) {
                                    validCustomList.add(name);
                                }
                            }
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < validCustomList.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(validCustomList.get(i));
                }
                editor.putString("custom_eq_list", sb.toString());
                editor.commit();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}