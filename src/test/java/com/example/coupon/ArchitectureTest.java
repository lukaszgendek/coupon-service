package com.example.coupon;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Keeps the dependency direction: adapters -> application -> domain, and adapters independent of each other. */
@AnalyzeClasses(packages = "com.example.coupon", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule domainDependsOnNothingElse = noClasses().that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..",
					"org.springframework..");

	@ArchTest
	static final ArchRule applicationIsFrameworkFreeAndDoesNotKnowAdapters = noClasses().that()
			.resideInAPackage("..application..")
			.should().dependOnClassesThat().resideInAnyPackage("..adapter..", "org.springframework..");

	@ArchTest
	static final ArchRule webTalksToTheApplicationOnlyThroughItsInboundPort = noClasses().that()
			.resideInAPackage("..adapter.web..")
			.should().dependOnClassesThat().resideInAnyPackage("..application", "..application.port.out..");

	@ArchTest
	static final ArchRule adaptersAreIndependent = slices().matching("..adapter.(*)..")
			.should().notDependOnEachOther();

}
