package com.ctds.common.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** 网关测试共用：HS256 自签测试令牌（无真实网络与真实密钥）。 */
final class GatewayJwtTestSupport {

    private GatewayJwtTestSupport() {
    }

    static JWTClaimsSet expiringClaims(final String subject, final List<String> roles) {
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", roles)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
    }

    static String signedToken(final byte[] key, final JWTClaimsSet claims) throws Exception {
        final SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        signed.sign(new MACSigner(key));
        return signed.serialize();
    }
}
