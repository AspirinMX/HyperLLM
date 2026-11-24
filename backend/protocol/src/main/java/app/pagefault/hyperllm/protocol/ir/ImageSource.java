package app.pagefault.hyperllm.protocol.ir;

/**
 * Polymorphic image payload source carried in {@link CanonicalContentPart.ImagePart}.
 */
public sealed interface ImageSource permits
        ImageSource.Base64Image,
        ImageSource.UrlImage,
        ImageSource.FileRefImage {

    /**
     * Inline base64 image without any {@code data:...;base64,} prefix.
     */
    record Base64Image(
            String mediaType,
            String base64Data
    ) implements ImageSource {
        public Base64Image {
            mediaType = (mediaType == null || mediaType.isBlank()) ? "image/png" : mediaType.trim();
            base64Data = base64Data == null ? "" : base64Data;
        }

        public String toDataUri() {
            return "data:" + mediaType + ";base64," + base64Data;
        }
    }

    /**
     * Public HTTP(S) image URL supported natively by all four protocols.
     */
    record UrlImage(
            String url,
            String mediaTypeHint
    ) implements ImageSource {
    }

    /**
     * Vendor-scoped file reference ({@code file_id}, Gemini Files API URI, or {@code gs://}).
     * Permitted on homomorphic passthrough; rejected fast on cross-vendor translation.
     */
    record FileRefImage(
            ProtocolKind ownerProtocol,
            String fileRef,
            String mediaTypeHint
    ) implements ImageSource {
    }
}
