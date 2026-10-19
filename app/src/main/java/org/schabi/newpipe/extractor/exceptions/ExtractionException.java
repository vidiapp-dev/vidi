package org.schabi.newpipe.extractor.exceptions;

/**
 * Minimal shim of NewPipe/PipePipe's {@code ExtractionException}.
 *
 * <p>The SABR code ported from PipePipe (GPLv3) throws this checked exception type from a couple of
 * places ({@code SabrProtocolException}, {@code SabrPoTokenProvider}). Rather than pull in the whole
 * NewPipe extractor, we provide this tiny stand-in with the same constructor surface.
 */
public class ExtractionException extends Exception {
    public ExtractionException(final String message) {
        super(message);
    }

    public ExtractionException(final Throwable cause) {
        super(cause);
    }

    public ExtractionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
