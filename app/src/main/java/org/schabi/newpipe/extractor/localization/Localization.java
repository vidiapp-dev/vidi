package org.schabi.newpipe.extractor.localization;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Minimal shim of NewPipe/PipePipe's {@code Localization}.
 *
 * <p>The ported {@link org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession}
 * threads a {@code Localization} through to the probe and reads {@link #getCountryCode()} when
 * building a {@link ContentCountry} for a reload. Our probe replacement ignores it for the media
 * POST, so only that small surface is needed.
 */
public class Localization {
    public static final Localization DEFAULT = new Localization("en", "US");

    @Nonnull
    private final String languageCode;
    @Nullable
    private final String countryCode;

    public Localization(@Nonnull final String languageCode) {
        this(languageCode, null);
    }

    public Localization(@Nonnull final String languageCode, @Nullable final String countryCode) {
        this.languageCode = languageCode;
        this.countryCode = countryCode;
    }

    @Nonnull
    public String getLanguageCode() {
        return languageCode;
    }

    @Nonnull
    public String getCountryCode() {
        return countryCode == null ? "" : countryCode;
    }

    @Nonnull
    public String getLocalizationCode() {
        return countryCode == null || countryCode.isEmpty()
                ? languageCode
                : languageCode + "-" + countryCode;
    }

    @Override
    public String toString() {
        return "Localization[" + getLocalizationCode() + "]";
    }
}
