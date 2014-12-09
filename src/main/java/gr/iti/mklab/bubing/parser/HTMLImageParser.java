package gr.iti.mklab.bubing.parser;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import gr.iti.mklab.image.Utils;
import gr.iti.mklab.image.VisualIndexer;
import gr.iti.mklab.simmo.items.Image;
import it.unimi.di.law.bubing.parser.BinaryParser;
import it.unimi.di.law.bubing.parser.HTMLParser;
import it.unimi.di.law.bubing.util.BURL;
import it.unimi.dsi.fastutil.io.InspectableFileCachedInputStream;
import net.htmlparser.jericho.StreamedSource;
import org.apache.commons.compress.utils.Charsets;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;

import net.htmlparser.jericho.CharacterReference;
import net.htmlparser.jericho.EndTag;
import net.htmlparser.jericho.EndTagType;
import net.htmlparser.jericho.HTMLElementName;
import net.htmlparser.jericho.Segment;
import net.htmlparser.jericho.StartTag;
import net.htmlparser.jericho.StartTagType;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.ws.rs.core.HttpHeaders;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class HTMLImageParser extends HTMLParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(HTMLImageParser.class);

    private final HashFunction hashFunction;

    //TODO: Replace this with a bloom filter
    private List<String> indexedImgs;

    public HTMLImageParser(final String messageDigest) throws NoSuchAlgorithmException {

        this(BinaryParser.forName(messageDigest));
    }

    public HTMLImageParser(final HashFunction hashFunction) {
        super(hashFunction);
        this.indexedImgs = new ArrayList<String>();
        this.hashFunction = hashFunction;
    }

    public void processImageURL(URI uri, URI base, StartTag startTag) throws MalformedURLException, IOException {
        System.out.println("processImageURL " + uri);
        String imgSrc = startTag.getAttributeValue("src");
        String altTxt = startTag.getAttributeValue("alt");

        URI url = BURL.parse(imgSrc);
        if (url != null) {
            URI resolved = base.resolve(url);
            Hasher hasher = hashFunction.newHasher();
            hasher.putBytes(resolved.toString().getBytes());
            String id = hasher.hash().toString();
            //avoid trying to index the same image multiple times
            if (!indexedImgs.contains(id)) {
                final URLConnection con = resolved.toURL().openConnection();

                if (Utils.checkContentHeaders(con.getContentLength(), con.getContentType())) {

                    BufferedImage image = ImageIO.read(con.getInputStream());
                    if (Utils.checkImage(image)) {

                        Image item = new Image();
                        item.setUrl(resolved.toString());
                        item.setTitle(altTxt);
                        item.setWidth(image.getWidth());
                        item.setHeight(image.getHeight());
                        item.setWebPageUrl(uri.toString());
                        item.setLastModifiedDate(new Date(con.getLastModified()));
                        item.setObjectId(new ObjectId());

                        if (VisualIndexer.getInstance().index(item)) {
                            indexedImgs.add(id);
                            //TODO: store in the DB
                        }
                    }
                }
            }
        }
    }

    @Override
    public byte[] parse(final URI uri, final HttpResponse httpResponse, final LinkReceiver linkReceiver) throws IOException {
        System.out.println("parser " + uri);
        guessedCharset = "ISO-8859-1";

        final HttpEntity entity = httpResponse.getEntity();

        // TODO: check if it will make sense to use getValue() of entity
        // Try to guess using headers
        final Header contentTypeHeader = entity.getContentType();
        if (contentTypeHeader != null) {
            final String headerCharset = getCharsetNameFromHeader(contentTypeHeader.getValue());
            if (headerCharset != null) guessedCharset = headerCharset;
        }

        final InputStream contentStream = entity.getContent();
        if (contentStream instanceof InspectableFileCachedInputStream) {
            final InspectableFileCachedInputStream inspectableStream = (InspectableFileCachedInputStream) contentStream;
            final String metaCharset = getCharsetName(inspectableStream.buffer, inspectableStream.inspectable);
            if (metaCharset != null) guessedCharset = metaCharset;
        }

        if (LOGGER.isDebugEnabled()) LOGGER.debug("Guessing charset \"{}\" for URL {}", guessedCharset, uri);

        Charset charset = Charsets.ISO_8859_1; // Fallback
        try {
            charset = Charset.forName(guessedCharset);
        } catch (IllegalCharsetNameException e) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Response for {} contained an illegal charset name: \"{}\"", uri, guessedCharset);
        } catch (UnsupportedCharsetException e) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Response for {} contained an unsupported charset: \"{}\"", uri, guessedCharset);
        }

        linkReceiver.init(uri);

        // Get location if present
        location = null;
        metaLocation = null;

        final Header locationHeader = httpResponse.getFirstHeader(HttpHeaders.LOCATION);
        if (locationHeader != null) {
            final URI location = BURL.parse(locationHeader.getValue());
            if (location != null) {
                // This shouldn't happen by standard, but people unfortunately does it.
                if (!location.isAbsolute() && LOGGER.isDebugEnabled())
                    LOGGER.debug("Found relative header location URL: \"{}\"", location);
                linkReceiver.location(this.location = uri.resolve(location));
            }
        }

        @SuppressWarnings("resource")
        final StreamedSource streamedSource = new StreamedSource(new InputStreamReader(contentStream, charset));
        if (buffer != null) streamedSource.setBuffer(buffer);
        if (digestAppendable != null) digestAppendable.init(crossAuthorityDuplicates ? null : uri);
        URI base = uri;

        int lastSegmentEnd = 0;
        int inSpecialText = 0;
        for (Segment segment : streamedSource) {
            if (segment.getEnd() > lastSegmentEnd) {
                lastSegmentEnd = segment.getEnd();
                if (segment instanceof StartTag) {
                    final StartTag startTag = (StartTag) segment;
                    if (startTag.getTagType() != StartTagType.NORMAL) continue;
                    final String name = startTag.getName();
                    if ((name == HTMLElementName.STYLE || name == HTMLElementName.SCRIPT) && !startTag.isSyntacticalEmptyElementTag())
                        inSpecialText++;


                    if (digestAppendable != null) digestAppendable.startTag(startTag);
                    if (linkReceiver == null) continue; // No link receiver, nothing to do.

                    // IFRAME or FRAME + SRC
                    //TODO: IFRAME and LINK should also be considered. What about Video?
                    if (name == HTMLElementName.IFRAME || name == HTMLElementName.FRAME || name == HTMLElementName.EMBED)
                        process(linkReceiver, base, startTag.getAttributeValue("src"));
                    else if (name == HTMLElementName.IMG)
                        processImageURL(uri, base, startTag);
                    else if (name == HTMLElementName.SCRIPT)
                        process(linkReceiver, base, startTag.getAttributeValue("src"));
                    else if (name == HTMLElementName.OBJECT)
                        process(linkReceiver, base, startTag.getAttributeValue("data"));
                    else if (name == HTMLElementName.A || name == HTMLElementName.AREA || name == HTMLElementName.LINK)
                        process(linkReceiver, base, startTag.getAttributeValue("href"));
                    else if (name == HTMLElementName.BASE) {
                        String s = startTag.getAttributeValue("href");
                        if (s != null) {
                            final URI link = BURL.parse(s);
                            if (link != null) {
                                if (link.isAbsolute()) base = link;
                                else if (LOGGER.isDebugEnabled()) LOGGER.debug("Found relative BASE URL: \"{}\"", link);
                            }
                        }
                    }

                    // META REFRESH/LOCATION
                    else if (name == HTMLElementName.META) {
                        final String equiv = startTag.getAttributeValue("http-equiv");
                        final String content = startTag.getAttributeValue("content");
                        if (equiv != null && content != null) {
                            equiv.toLowerCase();

                            // http-equiv="refresh" content="0;URL=http://foo.bar/..."
                            if (equiv.equals("refresh")) {

                                final int pos = URLEQUAL_PATTERN.search(content);
                                if (pos != -1) {
                                    final String urlPattern = content.substring(pos + URLEQUAL_PATTERN.length());
                                    final URI refresh = BURL.parse(urlPattern);
                                    if (refresh != null) {
                                        // This shouldn't happen by standard, but people unfortunately does it.
                                        if (!refresh.isAbsolute() && LOGGER.isDebugEnabled())
                                            LOGGER.debug("Found relative META refresh URL: \"{}\"", urlPattern);
                                        linkReceiver.metaRefresh(base.resolve(refresh));
                                    }
                                }
                            }

                            // http-equiv="location" content="http://foo.bar/..."
                            if (equiv.equals("location")) {
                                final URI metaLocation = BURL.parse(content);
                                if (metaLocation != null) {
                                    // This shouldn't happen by standard, but people unfortunately does it.
                                    if (!metaLocation.isAbsolute() && LOGGER.isDebugEnabled())
                                        LOGGER.debug("Found relative META location URL: \"{}\"", content);
                                    linkReceiver.metaLocation(this.metaLocation = base.resolve(metaLocation));
                                }
                            }
                        }
                    }
                } else if (segment instanceof EndTag) {
                    final EndTag endTag = (EndTag) segment;
                    final String name = endTag.getName();
                    if (name == HTMLElementName.STYLE || name == HTMLElementName.SCRIPT) {
                        inSpecialText = Math.max(0, inSpecialText - 1); // Ignore extra closing tags
                    }

                    if (digestAppendable != null) {
                        if (endTag.getTagType() != EndTagType.NORMAL) continue;
                        digestAppendable.endTag(endTag);
                    }
                } else if (digestAppendable != null && inSpecialText == 0) {
                    if (segment instanceof CharacterReference)
                        ((CharacterReference) segment).appendCharTo(digestAppendable);
                    else digestAppendable.append(segment);
                }
            }
        }

        /*if (DigestAppendable.DEBUG)
            if (digestAppendable != null) {
                System.err.println("Closing " + digestAppendable.debugFile + " for " + uri);
                digestAppendable.debugStream.close();
            }*/

        return digestAppendable != null ? digestAppendable.digest() : null;
    }

}