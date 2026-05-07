package com.agent.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtils {
    //声明一个密钥
    // Declare the secret key.
    private static final String SECRET_KEY = System.getenv("JWT_SECRET_KEY");
    //创建一个符合 HMAC-SHA 算法要求的 Key 对象
    // Create a Key object compatible with HMAC-SHA.
    private static final Key KEY = Keys.hmacShaKeyFor(SECRET_KEY.getBytes());
    //密钥有效期,默认7天
    // Token expiration time, defaulting to 7 days.
    private static final Long EXPIRATION_TIME = 1000 * 60 * 60 * 24 * 7L;
    //把用户ID和用户名作为参数传入，返回生成的Token
    // Generate a token from the user ID and username.
    public static String createToken(Long userId, String username){
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        return Jwts.builder()//创建JWT的构建器 / Create the JWT builder.
                .setClaims(claims)//添加自定义信息 / Add custom claims.
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))//设置过期时间 / Set the expiration time.
                .signWith(KEY, SignatureAlgorithm.HS256)//签名算法 / Sign with HS256.
                .compact();//生成最终的 JWT 字符串 / Generate the final JWT string.
    }

    //解析Token，返回用户ID和用户名
    // Parse the token and return the user ID and username.
    public static Claims parseToken(String token){
        return Jwts.parserBuilder()//创建JWT解析器 / Create the JWT parser.
                .setSigningKey(KEY)//设置密钥 / Set the signing key.
                .build()//构建解析器 / Build the parser.
                .parseClaimsJws(token)//解析Token / Parse the token.
                .getBody();//获取Claims对象 / Get the Claims object.
    }
}
