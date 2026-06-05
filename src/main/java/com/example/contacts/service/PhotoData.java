package com.example.contacts.service;

/**
 * Immutable carrier for a contact's photo bytes together with their MIME
 * content type.
 *
 * <p>Returned by {@link ContactService#getPhoto(Long)} so the lazily-loaded
 * blob is read inside the service transaction and handed to the controller as
 * a plain value object, avoiding {@code LazyInitializationException}.
 *
 * @param data        the raw image bytes
 * @param contentType the image MIME type (e.g. {@code image/png})
 */
public record PhotoData(byte[] data, String contentType) {
}
