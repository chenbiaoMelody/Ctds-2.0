-- KMS SM2 密钥对托管扩展（WBS-3.1.8，hifi §2.4）：kms_key 增加密钥类型与 SM2 公钥列；
-- 私钥 D 值仍存 kms_key_version.material_cipher（根密钥 SM4 信封），无私钥明文列（规格行为 1）。
ALTER TABLE kms_key
    ADD COLUMN key_type       VARCHAR(8)   NOT NULL DEFAULT 'SM4',
    ADD COLUMN public_key_hex VARCHAR(130) NULL;
