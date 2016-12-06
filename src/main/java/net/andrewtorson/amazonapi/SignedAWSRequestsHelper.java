package net.andrewtorson.amazonapi;

import com.google.common.base.Preconditions;
import com.typesafe.config.Config;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
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

    private final String _accessKeyID;
    private final String _associateTag;
    private final String _endpoint;
    private final Mac _mac;


    /**
     * All strings are handled as UTF-8
     */
    private static final String UTF8_CHARSET = "UTF-8";
    
    /**
     * The HMAC algorithm required by Amazon
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

    /**
     * @param config AWS credentials config
     * @param endpoint destination address for the requests
     */
    public SignedAWSRequestsHelper(Config config, String endpoint) throws IllegalArgumentException, UnsupportedEncodingException, NoSuchAlgorithmException, InvalidKeyException, DestroyFailedException {

        String errorMessageFormat = "%s is empty";

        _associateTag = config.getString("associateTag");
        _accessKeyID = config.getString("accessKeyID");
        _endpoint = endpoint.toLowerCase();

        Preconditions.checkArgument(!StringUtils.isEmpty(_associateTag), errorMessageFormat, "associateTag");
        Preconditions.checkArgument(!StringUtils.isEmpty(_accessKeyID), errorMessageFormat, "accessKeyID");
        Preconditions.checkArgument(!StringUtils.isEmpty(_endpoint), errorMessageFormat, "endpoint");

        _mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
        SecretKeySpec key = new SecretKeySpec(config.getString("secretKey").getBytes(UTF8_CHARSET), HMAC_SHA256_ALGORITHM);
        _mac.init(key);
        // no need to call 'destroy' on this secret key implementation
    }
    
    /**
     * This method signs requests in hashmap form. It returns a URL that should
     * be used to fetch the response. The URL returned should not be modified in
     * any way, doing so will invalidate the signature and Amazon will reject
     * the request.
     * @params map of AWS commercial API request params
     */
    public String sign(Map<String, String> params) throws UnsupportedEncodingException{
        Map<String,String> decoratedParams = new TreeMap<>(params); // it must be sorted alphabetically according to AWS docs (probably, used by AWS sharding)
        decoratedParams.put("AssociateTag", _associateTag);
        decoratedParams.put("AWSAccessKeyId", _accessKeyID);
        decoratedParams.put("Timestamp", this.timestamp());

        // get the canonical form the query string
        String canonicalQS = this.canonicalize(decoratedParams);
        
        // create the string upon which the signature is calculated 
        String toSign = 
            REQUEST_METHOD + "\n" 
            + this._endpoint + "\n"
            + REQUEST_URI + "\n"
            + canonicalQS;

        // get the signature
        String hmac = this.hmac(toSign);
        String sig = this.percentEncodeRfc3986(hmac);

        // construct the URL
        String url = 
            "http://" + this._endpoint + REQUEST_URI + "?" + canonicalQS + "&Signature=" + sig;

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
        byte[]rawHmac = _mac.doFinal(data);
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
