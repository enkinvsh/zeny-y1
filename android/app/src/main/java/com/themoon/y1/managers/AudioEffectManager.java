package com.themoon.y1.managers;

import com.themoon.y1.MainActivity;

/**
 * Передаёт настройки звука из MainActivity в {@link ZenyAudioProcessor}. Стоковые эффекты Android
 * (Equalizer, BassBoost, Virtualizer, LoudnessEnhancer) намеренно не создаются: они работают в AudioFlinger
 * уже после нашего 16-битного дизеринга и обрабатывали бы звук второй раз.
 */
public class AudioEffectManager {
    private static AudioEffectManager instance;

    private AudioEffectManager() {}

    public static synchronized AudioEffectManager getInstance() {
        if (instance == null) {
            instance = new AudioEffectManager();
        }
        return instance;
    }

    public void applyAudioEffects() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;

        ZenyAudioProcessor zeny = AudioPlayerManager.getInstance().zenyProcessor;
        zeny.setAirStep(main.currentAirStep);
        zeny.setCrossfeedStep(main.currentCrossfeedStep);
        zeny.setSoundCheckEnabled(main.isSoundCheckEnabled);
    }

    public void applySoundCheck() {
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        AudioPlayerManager.getInstance().zenyProcessor.setSoundCheckEnabled(main.isSoundCheckEnabled);
    }
}
