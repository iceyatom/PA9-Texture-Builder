package com.yourname.texturebuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants for PA9 TextureBuilder. */
public final class TextureBuilder {
    public static final String MOD_ID = "texturebuilder";
    public static final String MOD_NAME = "PA9 TextureBuilder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** The nine hotbar slots the mod is allowed to touch (FR-17). */
    public static final int HOTBAR_SIZE = 9;

    private TextureBuilder() {
    }
}
