package gov.nist.hit.core.hl7v2.api;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import gov.nist.hit.core.domain.TestCase;
import gov.nist.hit.core.domain.TestCaseGroup;
import gov.nist.hit.core.domain.TestPlan;
import gov.nist.hit.core.domain.TestScope;
import gov.nist.hit.core.domain.TestStep;
import gov.nist.hit.core.hl7v2.domain.HL7V2TestContext;

public class CBManagementControllerScopeTest {

	private final CBManagementController controller = new CBManagementController();

	@Test
	public void publishReachesGroupsNestedInsideGroups() {
		TestCase nestedCase = testCase();
		TestCaseGroup nested = group(nestedCase);
		TestCaseGroup deeper = group(testCase());
		nested.setTestCaseGroups(set(deeper));
		TestCaseGroup top = group(testCase());
		top.setTestCaseGroups(set(nested));

		TestPlan plan = new TestPlan();
		plan.setScope(TestScope.USER);
		plan.setTestCaseGroups(set(top));
		plan.setTestCases(new HashSet<TestCase>());

		controller.updateTestScope(plan, TestScope.GLOBAL);

		assertEquals(TestScope.GLOBAL, plan.getScope());
		assertEquals(TestScope.GLOBAL, top.getScope());
		assertEquals(TestScope.GLOBAL, nested.getScope());
		assertEquals(TestScope.GLOBAL, deeper.getScope());
		for (TestCaseGroup g : new TestCaseGroup[] { top, nested, deeper }) {
			for (TestCase c : g.getTestCases()) {
				assertEquals(TestScope.GLOBAL, c.getScope());
				for (TestStep s : c.getTestSteps()) {
					assertEquals(TestScope.GLOBAL, s.getScope());
					assertEquals(TestScope.GLOBAL, s.getTestContext().getScope());
				}
			}
		}
	}

	@Test
	public void unpublishReachesNestedGroupsToo() {
		TestCaseGroup nested = group(testCase());
		TestCaseGroup top = group(testCase());
		top.setTestCaseGroups(set(nested));
		for (TestCaseGroup g : new TestCaseGroup[] { top, nested }) {
			g.setScope(TestScope.GLOBAL);
			for (TestCase c : g.getTestCases()) {
				c.setScope(TestScope.GLOBAL);
				for (TestStep s : c.getTestSteps()) {
					s.setScope(TestScope.GLOBAL);
					s.getTestContext().setScope(TestScope.GLOBAL);
				}
			}
		}

		controller.updateTestScope(top, TestScope.USER);

		assertEquals(TestScope.USER, nested.getScope());
		TestStep nestedStep = nested.getTestCases().iterator().next().getTestSteps().iterator().next();
		assertEquals(TestScope.USER, nestedStep.getScope());
		assertEquals(TestScope.USER, nestedStep.getTestContext().getScope());
	}

	@Test
	public void stepWithoutContextDoesNotBreakPublishing() {
		TestStep bare = new TestStep();
		bare.setScope(TestScope.USER);
		TestCase c = new TestCase();
		c.setScope(TestScope.USER);
		c.setTestSteps(set(bare));

		controller.updateTestScope(c, TestScope.GLOBAL);

		assertEquals(TestScope.GLOBAL, bare.getScope());
	}

	private static TestCase testCase() {
		HL7V2TestContext context = new HL7V2TestContext();
		context.setScope(TestScope.USER);
		TestStep step = new TestStep();
		step.setScope(TestScope.USER);
		step.setTestContext(context);
		TestCase c = new TestCase();
		c.setScope(TestScope.USER);
		c.setTestSteps(set(step));
		return c;
	}

	private static TestCaseGroup group(TestCase c) {
		TestCaseGroup g = new TestCaseGroup();
		g.setScope(TestScope.USER);
		g.setTestCases(set(c));
		g.setTestCaseGroups(new HashSet<TestCaseGroup>());
		return g;
	}

	private static <T> Set<T> set(T one) {
		Set<T> s = new HashSet<T>();
		s.add(one);
		return s;
	}
}
