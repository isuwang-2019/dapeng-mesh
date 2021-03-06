package com.github.dapeng.gateway.http.match;

import com.github.dapeng.gateway.netty.request.RequestContext;
import com.github.dapeng.gateway.netty.request.RequestParser;
import com.github.dapeng.gateway.util.Constants;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;

/**
 * @author maple 2018.09.03 17:02  UrlMappingResolver
 */
public class UrlMappingResolver {
    private static Logger logger = LoggerFactory.getLogger(UrlMappingResolver.class);

    private static final Pattern POST_GATEWAY_PATTERN = Pattern.compile("/([^\\s|^/]*)/([^\\s|^/]*)/([^\\s|^/]*)/([^\\s|^/]*)(?:/([^\\s|^/]*))?");

    private static final Pattern POST_GATEWAY_PATTERN_1 = Pattern.compile("/([^\\s|^/]*)(?:/([^\\s|^/]*))?");

    private static final Pattern ECHO_PATTERN = Pattern.compile("/([^\\s|^/]*)/([^\\s|^/]*)/([^\\s|^/]*)/([^\\s|^/]*)");


    private static final String[] WILDCARD_CHARS = {"/", "?", "&", "="};

    private static final String DEFAULT_URL_PREFIX = "api";


    public static void handlerPostUrl(FullHttpRequest request, RequestContext context) {
        Matcher matcherFirst = POST_GATEWAY_PATTERN.matcher(request.uri());

        if (matcherFirst.matches()) {
            handlerMappingUrl(matcherFirst, request, context);
            return;
        }
        Matcher matcherSecond = POST_GATEWAY_PATTERN_1.matcher(request.uri());

        if (matcherSecond.matches()) {
            handlerRequestParam(matcherSecond, request, context);
            return;
        }

        context.isLegal(false);
        context.cause("no match is available");
    }


    /**
     * 解析 rest风格的请求,包括apiKey 和 没有 apiKey 的 请求
     * etc. /api/com.today.soa.idgen.service.IDService/1.0.0/genId/{apiKey}?cookie=234&user=maple
     * etc. /api/com.today.soa.idgen.service.IDService/1.0.0/genId
     *
     * @param request
     * @param context
     */
    private static void handlerMappingUrl(Matcher matcher, FullHttpRequest request, RequestContext context) {
        getRequestCookies(request, context);

        String prefix = matcher.group(1);
        String serviceName = matcher.group(2);
        String versionName = matcher.group(3);
        String methodName = matcher.group(4);
        String apiKey = matcher.group(5);
        //apiKey为null，也要解析 parameter
        if (apiKey == null) {
            UrlArgumentHolder holder = doResolveArgument(methodName);
            context.urlPrefix(prefix);
            context.service(serviceName);
            context.version(versionName);
            context.method(holder.getLastPath());

            String parameter = RequestParser.fastParseParam(request, "parameter");

            context.parameter(parameter);

            context.arguments(holder.getArgumentMap());
            return;
        }
        UrlArgumentHolder holder = doResolveArgument(apiKey);

        String timestamp = null;
        String secret = null;
        String secret2 = null;
        Map<String, String> argumentMap = holder.getArgumentMap();

        if (argumentMap.size() > 0) {
            if (argumentMap.containsKey("timestamp")) {
                timestamp = argumentMap.get("timestamp");
            }
            if (argumentMap.containsKey("secret")) {
                secret = argumentMap.get("secret");
            }
            if (argumentMap.containsKey("secret2")) {
                secret2 = argumentMap.get("secret2");
            }
        }

        if (timestamp == null) {
            timestamp = RequestParser.fastParseParam(request, "timestamp");
        }
        if (secret == null) {
            secret = RequestParser.fastParseParam(request, "secret");
        }
        if (secret2 == null) {
            secret2 = RequestParser.fastParseParam(request, "secret2");
        }

        String parameter = RequestParser.fastParseParam(request, "parameter");
        context.urlPrefix(prefix);
        context.service(serviceName);
        context.version(versionName);
        context.method(methodName);
        context.apiKey(holder.getLastPath());
        context.timestamp(timestamp);
        context.secret(secret);
        context.secret2(secret2);
        context.parameter(parameter);
        context.arguments(holder.getArgumentMap());
    }

    /**
     * 解析 requestParam 风格的请求,包括apiKey 和 没有 apiKey 的 请求
     * etc. /api/{apiKey}?cookie=234&user=maple
     * etc. /api?cookie=234&user=maple
     */
    private static void handlerRequestParam(Matcher matcher, FullHttpRequest request, RequestContext context) {
        getRequestCookies(request, context);

        String prefix = matcher.group(1);
        String apiKey = matcher.group(2);

        // prefix 必须以 api开头，否则为非法请求
        if (!prefix.equals(DEFAULT_URL_PREFIX)) {
            context.isLegal(false);
            context.cause("prefix 必须以 api开头");
            return;
        }

        if (apiKey == null) {
            UrlArgumentHolder holder = doResolveArgument(prefix);
            context.urlPrefix(holder.getLastPath());
            context.arguments(holder.getArgumentMap());
            RequestParser.fastParse(request, context);
            return;
        }
        UrlArgumentHolder holder = doResolveArgument(apiKey);

        context.urlPrefix(prefix);
        context.apiKey(holder.getLastPath());
        context.arguments(holder.getArgumentMap());

        RequestParser.fastParse(request, context);
    }

    /**
     * 解析url后携带参数,封装为 Map
     */
    private static UrlArgumentHolder doResolveArgument(String parameter) {
        try {
            // container ?
            int pos = parameter.lastIndexOf(WILDCARD_CHARS[1]);
            if (pos != -1) {
                String arguments = parameter.substring(pos + 1);
                if (arguments.contains(WILDCARD_CHARS[0])) {
                    return UrlArgumentHolder.onlyPathCreator(parameter);
                }

                UrlArgumentHolder holder = UrlArgumentHolder.nonPropertyCreator();
                Arrays.stream(arguments.split(WILDCARD_CHARS[2])).forEach(argument -> {
                    String[] arg = argument.split(WILDCARD_CHARS[3]);
                    holder.setArgument(arg[0], arg[1]);
                });
                holder.setLastPath(discardSuffuxIfNessary(parameter.substring(0, pos)));
                return holder;
            }
            return UrlArgumentHolder.onlyPathCreator(discardSuffuxIfNessary(parameter));
        } catch (RuntimeException e) {
            logger.error("解析url参数错误, exception: {}, cause:" + e.getMessage(), e.getClass().getName());
            return UrlArgumentHolder.onlyPathCreator(parameter);
        }
    }

    /**
     * 解析 rest风格的 echo get 请求  /api/echo/{service}/{version}
     */
    public static Pair<String, String> handlerEchoUrl(String url) {
        Matcher matcher = ECHO_PATTERN.matcher(url);

        try {
            if (matcher.matches()) {
                String prefix = "/" + matcher.group(1) + "/" + matcher.group(2);

                if (!prefix.equals(Constants.ECHO_PREFIX)) {
                    return null;
                }
                String serviceName = matcher.group(3);
                String versionName = matcher.group(4);

                return new Pair<>(serviceName, versionName);
            }
            return null;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;

        }
    }

    /**
     * Get Cookies from client
     *
     * @param request FullHttpRequest #netty
     * @param context RequestContext
     */
    private static void getRequestCookies(FullHttpRequest request, RequestContext context) {
        String value = request.headers().get(COOKIE);
        if (value != null) {
            context.cookies(ServerCookieDecoder.STRICT.decode(value));
        }
    }

    /**
     * 去除 url 带的后缀
     *
     * @param path
     * @return
     */
    private static String discardSuffuxIfNessary(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return path.substring(0, path.lastIndexOf("."));
        }
        return path;
    }

}
