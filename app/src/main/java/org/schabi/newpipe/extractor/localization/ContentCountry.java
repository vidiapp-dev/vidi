package org.schabi.newpipe.extractor.localization;

import javax.annotation.Nonnull;

/**
 * Minimal shim of NewPipe/PipePipe's {@code ContentCountry}. The ported
 * {@link org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession} only constructs one
 * (from a {@link Localization}'s country code) to pass to a reload, and our probe replacement just
 * forwards it, so the country-code accessor is the whole surface we need.
 */
public class ContentCountry {
    public static final ContentCountry DEFAULT = new ContentCountry("US");

    @Nonnull
    private final String countryCode;

    public ContentCountry(@Nonnull final String countryCode) {
        this.countryCode = countryCode;
    }

    @Nonnull
    public String getCountryCode() {
        return countryCode;
    }

    @Override
    public String toString() {
        return countryCode;
    }
}
