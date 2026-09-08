package com.ctds.common.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;
import org.bouncycastle.util.encoders.Hex;

/**
 * SM2 非对称统一入口（hifi B4/B5）：密钥对生成、加解密（C1C3C2，GB/T 32918.4 新版默认）、
 * 签名/验签（GB/T 32918.2，用户标识取国标默认值）。
 * 密钥为参数传入（hifi 差异声明 1：SM2 属主体身份类，SM4 才走 KeyProvider）。
 * 无状态：引擎与参数对象每次调用现场构造，实例可安全共享多线程。
 */
public class Sm2Service {

    private static final X9ECParameters X9 = GMNamedCurves.getByName("sm2p256v1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            X9.getCurve(), X9.getG(), X9.getN(), X9.getH());
    private static final int PUB_HEX_CHARS = 130;
    private static final int PRIV_HEX_CHARS = 64;
    private static final int PRIV_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** 生成密钥对（B4）：公钥非压缩点 04||X||Y hex（130 字符），私钥 D 值 hex（64 字符）。 */
    public Sm2KeyPair generateKeyPair() {
        final ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(DOMAIN, random));
        final AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        final ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();
        final ECPrivateKeyParameters priv = (ECPrivateKeyParameters) pair.getPrivate();
        return new Sm2KeyPair(Hex.toHexString(pub.getQ().getEncoded(false)),
                Hex.toHexString(BigIntegers.asUnsignedByteArray(PRIV_BYTES, priv.getD())));
    }

    /** 公钥加密（B4）。密文 C1C3C2 结构：0x04 前缀 + X(32) + Y(32) + SM3(32) + 明文长。 */
    public byte[] encrypt(final byte[] plaintext, final String publicKeyHex) {
        Inputs.requirePlaintext(plaintext);
        final ECPublicKeyParameters pub = publicKey(publicKeyHex);
        final SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
        engine.init(true, new ParametersWithRandom(pub, random));
        try {
            return engine.processBlock(plaintext, 0, plaintext.length);
        } catch (final InvalidCipherTextException e) {
            throw Inputs.operationFailed(e);
        }
    }

    /** 私钥解密（B4）：密文被改/结构坏 → DATA_REJECTED；hex 格式坏 → INPUT_INVALID。 */
    public byte[] decrypt(final byte[] cipherText, final String privateKeyHex) {
        Inputs.requireSm2CipherLength(cipherText);
        final ECPrivateKeyParameters priv = privateKey(privateKeyHex);
        final SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
        engine.init(false, priv);
        try {
            return engine.processBlock(cipherText, 0, cipherText.length);
        } catch (final InvalidCipherTextException | RuntimeException e) {
            // 密文结构坏（截断/非法点/篡改）统一 DATA_REJECTED（hifi 边界表：结构坏/被改=拒绝）
            throw Inputs.rejected();
        }
    }

    /** 私钥签名（B5）：输出 DER 编码（SEQUENCE{r,s}）。 */
    public byte[] sign(final byte[] data, final String privateKeyHex) {
        Inputs.requirePlaintext(data);
        final ECPrivateKeyParameters priv = privateKey(privateKeyHex);
        final SM2Signer signer = new SM2Signer();
        signer.init(true, new ParametersWithRandom(priv, random));
        signer.update(data, 0, data.length);
        try {
            return signer.generateSignature();
        } catch (final CryptoException e) {
            throw Inputs.operationFailed(e);
        }
    }

    /** 公钥验签（B5）：数据/签名被改、公钥不匹配 → false；仅数据为空或密钥 hex 格式坏抛 INPUT_INVALID。 */
    public boolean verify(final byte[] data, final byte[] signature, final String publicKeyHex) {
        Inputs.requirePlaintext(data);
        final ECPublicKeyParameters pub = publicKey(publicKeyHex);
        if (signature == null || signature.length == 0 || signature.length > Inputs.MAX_INPUT_BYTES) {
            throw Inputs.invalid();
        }
        final SM2Signer signer = new SM2Signer();
        signer.init(false, pub);
        signer.update(data, 0, data.length);
        try {
            return signer.verifySignature(signature);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private ECPublicKeyParameters publicKey(final String publicKeyHex) {
        if (publicKeyHex == null || publicKeyHex.length() != PUB_HEX_CHARS || !publicKeyHex.startsWith("04")) {
            throw Inputs.invalid();
        }
        final byte[] encoded;
        final ECPoint point;
        try {
            encoded = Hex.decode(publicKeyHex);
            point = DOMAIN.getCurve().decodePoint(encoded);
        } catch (final RuntimeException e) {
            throw Inputs.invalid();
        }
        return new ECPublicKeyParameters(point, DOMAIN);
    }

    private ECPrivateKeyParameters privateKey(final String privateKeyHex) {
        if (privateKeyHex == null || privateKeyHex.length() != PRIV_HEX_CHARS) {
            throw Inputs.invalid();
        }
        final BigInteger d;
        try {
            d = new BigInteger(1, Hex.decode(privateKeyHex));
        } catch (final RuntimeException e) {
            throw Inputs.invalid();
        }
        if (d.signum() <= 0 || d.compareTo(DOMAIN.getN()) >= 0) {
            throw Inputs.invalid();
        }
        return new ECPrivateKeyParameters(d, DOMAIN);
    }
}
