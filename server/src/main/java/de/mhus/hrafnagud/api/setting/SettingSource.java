package de.mhus.hrafnagud.api.setting;

/** Which layer a setting's effective value comes from. */
public enum SettingSource {

    /** An override stored in the {@code settings} collection. */
    DATABASE,

    /** No override: {@code application.yml}, an environment variable, or the code default. */
    CONFIG
}
