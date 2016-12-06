package net.andrewtorson.amazonapi;

import com.google.common.base.Preconditions;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * This class contains all the logic for signing requests
 * to the Amazon Product Advertising API.
 */
public class SignedAWSRequestsHelper {

    private static final String AWS_ACCESS_KEY_ID = "XXXXXXXXXXX";
    private static final String AWS_SECRET_KEY = "XXXXXXXXXXXXXXXXXX";
    private static final String AWS_ASSOCIATE_TAG = "XXXXXXX";


    /**
     * All strings are handled as UTF-8
     */
    private static final String UTF8_CHARSET = "UTF-8";
    
    /**
     * The HMAC algorithm required by Amazon
     */    /*
     * Utility function to fetch the response from the service and extract the
     * title from the XML.
     */

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    
    /**
     * This is the URI for the service, don't change unless you really know
     * what you're doing.
     */
    private static final String REQUEST_URI = "/onca/xml";
    
    /**
     * The sample uses HTTP GET to fetch the response. If you changed the sample
     * to use HTTP POST instead, change the value below to POST. 
     */
    private static final String REQUEST_METHOD = "GET";


    private String endpoint = null;

    private SecretKeySpec secretKeySpec = null;
    private Mac mac = null;

    /**
     * @param endpoint Destination for the requests.
     */
    public static SignedAWSRequestsHelper getInstance(String endpoint) throws IllegalArgumentException, UnsupportedEncodingException, NoSuchAlgorithmException, InvalidKeyException
    {
        Preconditions.checkArgument(!StringUtils.isEmpty(endpoint),"%s is null or empty", "endpoint");

        SignedAWSRequestsHelper instance = new SignedAWSRequestsHelper();
        instance.endpoint = endpoint.toLowerCase();

        byte[] secretyKeyBytes = AWS_SECRET_KEY.getBytes(UTF8_CHARSET);
        instance.secretKeySpec = new SecretKeySpec(secretyKeyBytes, HMAC_SHA256_ALGORITHM);
        instance.mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
        instance.mac.init(instance.secretKeySpec);

        return instance;
    }
    
    /**
     * The construct is private since we'd rather use getInstance()
     */
    private SignedAWSRequestsHelper() {}

    /**
     * This method signs requests in hashmap form. It returns a URL that should
     * be used to fetch the response. The URL returned should not be modified in
     * any way, doing so will invalidate the signature and Amazon will reject
     * the request.
     */
    public String sign(Map<String, String> params) throws UnsupportedEncodingException{
        Map<String,String> decoratedParams = new TreeMap<>(params);
        decoratedParams.put("AssociateTag", AWS_ASSOCIATE_TAG);
        decoratedParams.put("AWSAccessKeyId", AWS_ACCESS_KEY_ID);
        decoratedParams.put("Timestamp", this.timestamp());

        // get the canonical form the query string
        String canonicalQS = this.canonicalize(decoratedParams);
        
        // create the string upon which the signature is calculated 
        String toSign = 
            REQUEST_METHOD + "\n" 
            + this.endpoint + "\n"
            + REQUEST_URI + "\n"
            + canonicalQS;

        // get the signature
        String hmac = this.hmac(toSign);
        String sig = this.percentEncodeRfc3986(hmac);

        // construct the URL
        String url = 
            "http://" + this.endpoint + REQUEST_URI + "?" + canonicalQS + "&Signature=" + sig;

        return url;
    }

    /**
     * Compute the HMAC.
     *  
     * @param stringToSign  String to compute the HMAC over.
     * @return              base64-encoded hmac value.
     */
    private String hmac(String stringToSign) throws UnsupportedEncodingException{
        byte[] data = stringToSign.getBytes(UTF8_CHARSET);
        byte[]rawHmac = mac.doFinal(data);
        Base64 encoder = new Base64();
        String sig = new String(encoder.encode(rawHmac));
        return sig.trim();
    }

    /**
     * Generate a ISO-8601 format timestamp as required by Amazon.
     *  
     * @return  ISO-8601 format timestamp.
     */
    private String timestamp() {
        Calendar cal = Calendar.getInstance();
        DateFormat dfm = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        dfm.setTimeZone(TimeZone.getTimeZone("GMT"));
        return dfm.format(cal.getTime());
    }

    /**
     * Canonicalize the query string as required by Amazon.
     * 
     * @param params   Parameter name-value pairs
     * @return         Canonical form of query string.
     */
    private String canonicalize(Map<String, String> params) throws UnsupportedEncodingException{
        if (params.isEmpty()) {
            return "";
        }
        StringBuffer buffer = new StringBuffer();
        Iterator<Map.Entry<String, String>> iter = params.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, String> kvpair = iter.next();
            buffer.append(percentEncodeRfc3986(kvpair.getKey()));
            buffer.append("=");
            buffer.append(percentEncodeRfc3986(kvpair.getValue()));
            if (iter.hasNext()) {
                buffer.append("&");
            }
        }
        return buffer.toString();
    }

    /**
     * Percent-encode values according the RFC 3986. The built-in Java
     * URLEncoder does not encode according to the RFC, so we make the
     * extra replacements.
     * 
     * @param s decoded string
     * @return  encoded string per RFC 3986
     */
    private String percentEncodeRfc3986(String s) throws UnsupportedEncodingException{
        return URLEncoder.encode(s, UTF8_CHARSET)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }

}
