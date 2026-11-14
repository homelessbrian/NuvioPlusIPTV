package android.util;
import org.xmlpull.v1.XmlPullParser;
import org.kxml2.io.KXmlParser;
/** Host benchmark factory only. Both parser versions receive the same backend. */
public final class Xml {
    public static XmlPullParser newPullParser() { return new KXmlParser(); }
}
