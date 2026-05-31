/*
 * Copyright (c) 2021-2031, 河北计全科技有限公司 (https://www.jeequan.com & jeequan@126.com).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jeequan.jeepay.core.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/*
 * JWT工具包
 * 升级到 jjwt 0.12.x：使用新版流式 API
 * - 新版要求 HS512 至少 64 字节密钥；HS256 至少 32 字节
 * - 旧 yml 中 16 字节密钥需在生产环境通过 JEEPAY_JWT_SECRET 注入更长值
 *
 * @author terrfly
 * @site https://www.jeequan.com
 * @date 2021/6/8 16:32
 */
public class JWTUtils {

    /**
     * 将字符串密钥转换为符合 HS512 长度要求的 SecretKey
     * 注意事项：旧密钥（16 字节）会被右补 0 至 64 字节以满足 HS512 最小长度，保持向后兼容
     */
    private static SecretKey buildKey(String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 64) {
            byte[] padded = new byte[64];
            System.arraycopy(raw, 0, padded, 0, raw.length);
            raw = padded;
        }
        return Keys.hmacShaKeyFor(raw);
    }

    /** 生成token **/
    public static String generateToken(JWTPayload jwtPayload, String jwtSecret) {
        return Jwts.builder()
                .claims(jwtPayload.toMap())
                //过期时间 = 当前时间 + （设置过期时间[单位 ：s ] ）  token放置redis 过期时间无意义
                //.expiration(new Date(System.currentTimeMillis() + (jwtExpiration * 1000) ))
                .signWith(buildKey(jwtSecret), Jwts.SIG.HS512)
                .compact();
    }

    /** 根据token与秘钥 解析token并转换为 JWTPayload **/
    public static JWTPayload parseToken(String token, String secret){
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(buildKey(secret))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            JWTPayload result = new JWTPayload();
            result.setSysUserId(claims.get("sysUserId", Long.class));
            result.setCreated(claims.get("created", Long.class));
            result.setCacheKey(claims.get("cacheKey", String.class));
            return result;


        } catch (Exception e) {
            return null; //解析失败
        }
    }


}
