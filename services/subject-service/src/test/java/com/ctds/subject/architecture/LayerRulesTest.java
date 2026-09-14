package com.ctds.subject.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 分层依赖规则（章程 4.3，模式同 kms/example-service）：违反即单元测试门禁红灯。
 */
@AnalyzeClasses(packages = "com.ctds.subject", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerRulesTest {

    @ArchTest
    static final ArchRule interfacesMustNotAccessInfrastructure = noClasses()
            .that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule domainMustBeIndependent = classes()
            .that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..common.errorcode..", "..domain..");

    @ArchTest
    static final ArchRule applicationMustNotAccessInterfaces = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..interfaces..");

    /** 渠道收口纪律守卫（规格行为 7 第 1 条 / ADR-008；WBS-3.1.6 T7）：业务三层（domain/application/interfaces）
     *  只见 CertificationStandardApi 接口，禁止依赖模拟渠道具体实现类——真实渠道替换时业务代码零改动的机器保证。
     *  infrastructure 的 CertificationBeanConfig 为 ADR-008 §4 明示替换点（Bean 注册装配），不在禁内。 */
    @ArchTest
    static final ArchRule businessLayersMustNotDependOnMockChannel = noClasses()
            .that().resideInAnyPackage("com.ctds.subject.domain..", "com.ctds.subject.application..",
                    "com.ctds.subject.interfaces..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.ctds.std.certification.MockCertificationChannel");
}
