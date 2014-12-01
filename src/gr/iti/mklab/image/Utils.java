package gr.iti.mklab.image;

import java.awt.image.BufferedImage;

/**
 * Created by kandreadou on 12/1/14.
 */
public class Utils {

    private final static int MIN_CONTENT_LENGTH = 20000;
    private final static int MIN_WIDTH = 300;
    private final static int MIN_HEIGHT = 300;

    private final static String TYPE_JPEG = "image/jpeg";
    private final static String TYPE_PNG = "image/png";

    public static boolean checkContentHeaders(int contentLength, String contentType) {
        return contentLength > MIN_CONTENT_LENGTH && (TYPE_JPEG.equalsIgnoreCase(contentType) || TYPE_PNG.equalsIgnoreCase(contentType));
    }

    public static boolean checkImage(BufferedImage img) {
        return img != null && img.getWidth() >= MIN_WIDTH && img.getHeight() >= MIN_HEIGHT;
    }

}
