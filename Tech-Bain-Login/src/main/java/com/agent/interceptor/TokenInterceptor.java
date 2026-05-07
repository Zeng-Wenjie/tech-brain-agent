package com.agent.interceptor;

import com.agent.utils.JwtUtils;
import com.agent.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class TokenInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //遇到浏览器的 OPTIONS 预检请求，直接放行！
        // Let browser OPTIONS preflight requests pass through.
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        // 1. 获取请求头中的token
        // 1. Read the token from the request header.
        String token = request.getHeader("token");
        log.info("获取请求头中的token：{}", token);
        if (token == null || token.isEmpty()) {
            log.info("token为空");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        //2. 验证token
        // 2. Validate the token.
        try {
            //把解析Token的结果存储在claims对象中
            // Store the parsed token result in the claims object.
            io.jsonwebtoken.Claims claims = JwtUtils.parseToken(token);
            //获取用户ID,存入ThreadLocal中
            // Store the user ID in ThreadLocal.
            UserContext.setUserId(claims.get("userId", Long.class));
        } catch (Exception e) {
            log.info("token验证失败");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        //3. 放行
        // 3. Continue the request.
        log.info("token验证成功");
        return true;


    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserContext.removeUserId();
    }
}
