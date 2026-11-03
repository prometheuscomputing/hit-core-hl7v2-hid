/**
 * This software was developed at the National Institute of Standards and Technology by employees of
 * the Federal Government in the course of their official duties. Pursuant to title 17 Section 105
 * of the United States Code this software is not subject to copyright protection and is in the
 * public domain. This is an experimental system. NIST assumes no responsibility whatsoever for its
 * use by other parties, and makes no guarantees, expressed or implied, about its quality,
 * reliability, or any other characteristic. We would appreciate acknowledgement if the software is
 * used. This software can be redistributed and/or modified freely provided that any derivative
 * works bear some notice that they are derived from it, and any modified versions bear some notice
 * that they have been modified.
 */

package gov.nist.hit.core.hl7v2.api;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.nist.auth.hit.core.domain.Account;
import gov.nist.hit.core.api.AccountController;
import gov.nist.hit.core.api.SessionContext;
import gov.nist.hit.core.domain.AbstractTestCase;
import gov.nist.hit.core.domain.ResourceType;
import gov.nist.hit.core.domain.ResourceUploadAction;
import gov.nist.hit.core.domain.ResourceUploadResult;
import gov.nist.hit.core.domain.ResourceUploadStatus;
import gov.nist.hit.core.domain.TestArtifact;
import gov.nist.hit.core.domain.TestCase;
import gov.nist.hit.core.domain.TestCaseCommand;
import gov.nist.hit.core.domain.TestCaseGroup;
import gov.nist.hit.core.domain.TestPlan;
import gov.nist.hit.core.domain.TestScope;
import gov.nist.hit.core.domain.TestStep;
import gov.nist.hit.core.domain.TestStepValidationReport;
import gov.nist.hit.core.domain.TestingStage;
import gov.nist.hit.core.hl7v2.domain.HL7V2TestContext;
import gov.nist.hit.core.hl7v2.service.FileValidationHandler;
import gov.nist.hit.core.hl7v2.service.impl.HL7V2ResourceLoaderImpl;
import gov.nist.hit.core.hl7v2.service.impl.PackagingHandlerImpl;
import gov.nist.hit.core.service.AccountService;
import gov.nist.hit.core.service.AppInfoService;
import gov.nist.hit.core.service.BundleHandler;
import gov.nist.hit.core.service.TestArtifactService;
import gov.nist.hit.core.service.TestCaseGroupService;
import gov.nist.hit.core.service.TestCaseService;
import gov.nist.hit.core.service.TestPlanService;
import gov.nist.hit.core.service.TestStepService;
import gov.nist.hit.core.service.TestStepValidationReportService;
import gov.nist.hit.core.service.UserIdService;
import gov.nist.hit.core.service.UserService;
import gov.nist.hit.core.service.exception.MessageUploadException;
import gov.nist.hit.core.service.exception.NoUserFoundException;
import gov.nist.hit.hl7.profile.validation.domain.ProfileValidationReport;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

/**
 * @author Harold Affo (NIST)
 * 
 */
@RequestMapping("/cb/management")
@RestController
@Api(value = "Context-based Testing", tags = "Context-free Testing", position = 1)
public class CBManagementController {

	static final  Logger logger = LogManager.getLogger(CBManagementController.class);

	
	// public static final String CB_UPLOAD_DIR = new
	// File(System.getProperty("java.io.tmpdir")).getAbsolutePath() + "/cb";

//	@Value("${UPLOADED_RESOURCE_BUNDLE:/sites/data/uploaded_resource_bundles}")
//	private String UPLOADED_RESOURCE_BUNDLE;

	public String CB_RESOURCE_BUNDLE_DIR;

	@Autowired
	private TestStepService testStepService;

	@Autowired
	private TestPlanService testPlanService;

	@Autowired
//	@Qualifier("resourceLoader")
	private HL7V2ResourceLoaderImpl resourceLoader;

	@Autowired
	private TestCaseService testCaseService;

	@Autowired
	private TestCaseGroupService testCaseGroupService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserService userService;

	@Autowired
	private UserIdService userIdService;

	@Value("${server.email}")
	private String SERVER_EMAIL;

	@Autowired
	private BundleHandler bundleHandler;

	@Value("${mail.tool}")
	private String TOOL_NAME;

	@Autowired
	private TestArtifactService testArtifactService;

	@Autowired
	private TestStepValidationReportService reportService;

	@Autowired
	private AppInfoService appInfoService;

	@Autowired
	private FileValidationHandler fileValidationHandler;

	@Autowired
	private PackagingHandlerImpl packagingHandlerImpl;

	@PostConstruct
	public void init() {
		CB_RESOURCE_BUNDLE_DIR = appInfoService.getUploadsFolderPath() + "/cb";
	}

	@RequestMapping(value = "/testPlans", method = RequestMethod.GET, produces = "application/json")
	public List<TestPlan> getTestPlans(@ApiParam(value = "the scope of the test plans", required = false) @RequestParam(required = false) TestScope scope,
			HttpServletRequest request, HttpServletResponse response, @RequestParam(required = true) String domain) throws Exception {
		checkManagementSupport();
		logger.info("Fetching all testplans of type=" + scope + "...");
		List<TestPlan> results = null;
		scope = scope == null ? TestScope.GLOBAL : scope;
		String username = null;
		Long userId = SessionContext.getCurrentUserId(request.getSession(false));
		if (userId != null) {
			Account account = accountService.findOne(userId);
			if (account != null) {
				username = account.getUsername();
				String email = account.getEmail();
				if (userService.isAdminByEmail(email) || userService.isAdmin(username)) {
					if (scope.equals(TestScope.GLOBALANDUSER)) {
						results = testPlanService.findShortAllByStageAndDomain(TestingStage.CB, domain);
					} else {
						results = testPlanService.findShortAllByStageAndScopeAndDomain(TestingStage.CB, scope, domain);
					}
				} else {
					if (scope.equals(TestScope.GLOBALANDUSER)) {
						results = testPlanService.findAllShortByStageAndUsernameAndDomain(TestingStage.CB, username, domain); 
					} else {
						results = testPlanService.findAllShortByStageAndUsernameAndScopeAndDomain(TestingStage.CB, username, scope, domain);
					}
				}
			}
		}
		return results;
	}

	@ApiOperation(value = "Find a context-based test plan by its id", nickname = "getOneTestPlanById")
	@RequestMapping(value = "/testPlans/{testPlanId}", method = RequestMethod.GET, produces = "application/json")
	public TestPlan testPlan(@ApiParam(value = "the id of the test plan", required = true) @PathVariable final Long testPlanId, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		logger.info("Fetching  test case...");
		checkManagementSupport();
		TestPlan testPlan = testPlanService.findOne(testPlanId);
		return testPlan;
	}
	
	@RequestMapping(value = "/testPlans/{testPlanId}/testStepsWithExternalValueSets", method = RequestMethod.GET, produces = "application/json")
	public List<TestStep> testStepsWithExternalValueSets(@ApiParam(value = "the id of the test plan", required = true) @PathVariable final Long testPlanId, HttpServletRequest request,
			HttpServletResponse response) {
		try {
		logger.info("Fetching  test steps with external Values Sets...");
		checkManagementSupport();
		TestPlan testPlan = testPlanService.findOne(testPlanId);
			List<TestStep> list = new ArrayList<TestStep>();
			List<TestStep> stList = testPlan.getAllTestSteps();
			for(TestStep ts : stList) {
				HL7V2TestContext  tc = (HL7V2TestContext)ts.getTestContext();
				if(tc !=null && tc.getApikeys() !=null && !tc.getApikeys().isEmpty()) {
					list.add(ts);
				}
			}
		return list;
		}catch(Exception e) {
			System.out.println(e);
			return null;
		}
		
	}
	
	
	@RequestMapping(value = "/testStepsWithExternalValueSetsFromAllTestPlans", method = RequestMethod.GET, produces = "application/json")
	public List<TestStep> gettestStepsWithExternalValueSetsFromAllTestPlans(
			@ApiParam(value = "the scope of the test plans", required = false) @RequestParam(required = false) TestScope scope,
			@ApiParam(value = "the domain of the test plans", required = true) @RequestParam(required = true) String domain, HttpServletRequest request,
			HttpServletResponse response) {
		try {
			logger.info("Fetching  test steps with external Values Sets...");
			checkManagementSupport();

			List<TestPlan> testplanIds = null;
			String username = null;
			Long userId = SessionContext.getCurrentUserId(request.getSession(false));
			if (userId != null) {
				Account account = accountService.findOne(userId);
				if (account != null) {
					username = account.getUsername();
					String email = account.getEmail();
					if (userService.isAdminByEmail(email) || userService.isAdmin(username)) {
						if (scope.equals(TestScope.GLOBALANDUSER)) {
							testplanIds = testPlanService.findAllTestPlanIdsByDomain(domain);
						} else {
							testplanIds = testPlanService.findAllTestPlanIdsByScopeAndDomain(scope, domain);
						}
					} else {
						if (scope.equals(TestScope.GLOBALANDUSER)) {
							testplanIds = testPlanService.findAllTestPlanIdsByUsernameAndDomain(username, domain); 
						} else {
							testplanIds = testPlanService.findAllTestPlanIdsByScopeAndUsernameAndDomain(scope, username, domain);
						}
					}
				}
				List<TestStep> list = new ArrayList<TestStep>();

				for (TestPlan testplan : testplanIds) {
					
					TestPlan testPlan = testPlanService.findOne(testplan.getId());
					List<TestStep> stList = testPlan.getAllTestSteps();
					for(TestStep ts : stList) {
						HL7V2TestContext  tc = (HL7V2TestContext)ts.getTestContext();
						if(!tc.getApikeys().isEmpty()) {
							list.add(ts);
						}
					}
					
					
//					List<TestStep> stList = testplan.getAllTestSteps();
//					for (TestStep ts : stList) {
//						HL7V2TestContext tc = (HL7V2TestContext) ts.getTestContext();
//						if (!tc.getApikeys().isEmpty()) {
//							list.add(ts);
//						}
//					}
				}
				return list;
			}
			return null;
		
		} catch (Exception e) {
			return null;
		}
	}
	
	@RequestMapping(value = "/testPlanIds", method = RequestMethod.GET, produces = "application/json")
	public List<TestPlan> getTestPlanIds(
			@ApiParam(value = "the scope of the test plans", required = false) @RequestParam(required = false) TestScope scope,
			@ApiParam(value = "the domain of the test plans", required = true) @RequestParam(required = true) String domain, HttpServletRequest request,
			HttpServletResponse response) {
		try {
			logger.info("Fetching  test steps with external Values Sets...");
			checkManagementSupport();

			List<TestPlan> testplanIds = null;
			String username = null;
			Long userId = SessionContext.getCurrentUserId(request.getSession(false));
			if (userId != null) {
				Account account = accountService.findOne(userId);
				if (account != null) {
					username = account.getUsername();
					String email = account.getEmail();
					if (userService.isAdminByEmail(email) || userService.isAdmin(username)) {
						if (scope.equals(TestScope.GLOBALANDUSER)) {
							testplanIds = testPlanService.findAllTestPlanIdsByDomain(domain);
						} else {
							testplanIds = testPlanService.findAllTestPlanIdsByScopeAndDomain(scope, domain);
						}
					} else {
						if (scope.equals(TestScope.GLOBALANDUSER)) {
							testplanIds = testPlanService.findAllTestPlanIdsByUsernameAndDomain(username, domain); 
						} else {
							testplanIds = testPlanService.findAllTestPlanIdsByScopeAndUsernameAndDomain(scope, username, domain);
						}
					}
				}
				
				}
				return testplanIds;
				
		
		} catch (Exception e) {
			return null;
		}
	}


	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/testPlans/{testPlanId}/testCases/{testCaseId}/delete", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus deleteTestCase(HttpServletRequest request, @PathVariable("testCaseId") Long testCaseId,
			@PathVariable("testPlanId") Long testPlanId, Principal p) throws Exception {
		checkManagementSupport();
		TestPlan tp = testPlanService.findOne(testPlanId);
		if (tp != null) {
			TestCase testCase = testCaseService.findOne(testCaseId);
			if (testCase != null) {
				checkPermission(testCaseId, testCase, p);
				TestCase found = findTestCase(tp.getTestCases(), testCaseId);
				tp.getTestCases().remove(found);
				testPlanService.save(tp);
				ResourceUploadStatus result = deleteTestCase(testCase);
				return result;
			} else {
				ResourceUploadStatus result = new ResourceUploadStatus();
				result.setType(ResourceType.TESTCASE);
				result.setAction(ResourceUploadAction.DELETE);
				result.setId(testCaseId);
				result.setMessage("TestCase(" + testCaseId + ") Not found");
				result.setStatus(ResourceUploadResult.FAILURE);
				return result;
			}
		} else {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setType(ResourceType.TESTCASE);
			result.setAction(ResourceUploadAction.DELETE);
			result.setId(testCaseId);
			result.setMessage("TestPlan(" + testPlanId + ") Not found");
			result.setStatus(ResourceUploadResult.FAILURE);
			return result;
		}
	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/testCaseGroups/{testCaseGroupId}/testCases/{testCaseId}/delete", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus deleteTestCaseGroupTestCase(HttpServletRequest request, @PathVariable("testCaseId") Long testCaseId,
			@PathVariable("testCaseGroupId") Long testCaseGroupId, Principal p) throws Exception {
		checkManagementSupport();
		TestCaseGroup tp = testCaseGroupService.findOne(testCaseGroupId);
		if (tp != null) {
			TestCase testCase = testCaseService.findOne(testCaseId);
			if (testCase != null) {
				checkPermission(testCaseId, testCase, p);
				TestCase found = findTestCase(tp.getTestCases(), testCaseId);
				tp.getTestCases().remove(found);
				testCaseGroupService.save(tp);
				ResourceUploadStatus result = deleteTestCase(testCase);
				return result;
			} else {
				ResourceUploadStatus result = new ResourceUploadStatus();
				result.setType(ResourceType.TESTCASE);
				result.setAction(ResourceUploadAction.DELETE);
				result.setId(testCaseId);
				result.setMessage("TestCase(" + testCaseId + ") Not found");
				result.setStatus(ResourceUploadResult.FAILURE);
				return result;
			}
		} else {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setType(ResourceType.TESTCASE);
			result.setAction(ResourceUploadAction.DELETE);
			result.setId(testCaseId);
			result.setMessage("TestCaseGroup(" + testCaseGroupId + ") Not found");
			result.setStatus(ResourceUploadResult.FAILURE);
			return result;
		}

	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/testCases/{testCaseId}/testSteps/{testStepId}/delete", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus deleteTestStep(HttpServletRequest request, @PathVariable("testStepId") Long testStepId,
			@PathVariable("testCaseId") Long testCaseId, Principal p) throws Exception {
		checkManagementSupport();
		TestCase tp = testCaseService.findOne(testCaseId);
		if (tp != null) {
			TestStep testStep = testStepService.findOne(testStepId);
			if (testStep != null) {
				checkPermission(testStepId, testStep, p);
				TestStep found = findTestStep(tp.getTestSteps(), testStepId);
				tp.getTestSteps().remove(found);
				testCaseService.save(tp);
				ResourceUploadStatus result = deleteTestStep(testStep);
				return result;
			} else {
				ResourceUploadStatus result = new ResourceUploadStatus();
				result.setType(ResourceType.TESTSTEP);
				result.setAction(ResourceUploadAction.DELETE);
				result.setId(testCaseId);
				result.setMessage("TestStep(" + testStepId + ") Not found");
				result.setStatus(ResourceUploadResult.FAILURE);
				return result;
			}
		} else {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setType(ResourceType.TESTSTEP);
			result.setAction(ResourceUploadAction.DELETE);
			result.setId(testCaseId);
			result.setMessage("TestCase(" + testCaseId + ") Not found");
			result.setStatus(ResourceUploadResult.FAILURE);
			return result;
		}
	}

	@RequestMapping(value = "/testPlans/{testPlanId}/delete", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus deleteTestPlan(HttpServletRequest request, @PathVariable("testPlanId") Long testPlanId, Principal p) throws Exception {
		checkManagementSupport();
		TestPlan testPlan = testPlanService.findOne(testPlanId);
		checkPermission(testPlanId, testPlan, p);
		testPlanService.delete(testPlan);
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTPLAN);
		result.setAction(ResourceUploadAction.DELETE);
		result.setId(testPlan.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	private ResourceUploadStatus deleteTestCase(TestCase tp) throws Exception {
		checkManagementSupport();
		testCaseService.delete(tp);
		// if (testSteps != null) {
		// for (TestStep testStep : testSteps) {
		// deleteTestStep(testStep);
		// }
		// }
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTCASE);
		result.setAction(ResourceUploadAction.DELETE);
		result.setId(tp.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	private ResourceUploadStatus deleteTestStep(TestStep testStep) throws Exception {
		checkManagementSupport();
		List<TestStepValidationReport> reports = reportService.findAllByTestStep(testStep.getId());
		if (reports != null) {
			reportService.delete(reports);
		}
		testStepService.delete(testStep);
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTSTEP);
		result.setAction(ResourceUploadAction.DELETE);
		result.setId(testStep.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/testSteps/{testStepId}/name", method = RequestMethod.POST)
	public ResourceUploadStatus updateTestStepName(HttpServletRequest request, @PathVariable("testStepId") Long testStepId, Principal p,
			@RequestBody TestCaseCommand ts) throws Exception {
		checkManagementSupport();
		String name = ts.getName();
		TestStep testStep = testStepService.findOne(testStepId);
		checkPermission(testStepId, testStep, p);
		testStep.setName(name);
		testStepService.save(testStep);

		TestPlan tp = testPlanService.findTestPlanContainingAbstractTestCase(testStep);
		tp.updateUpdateDate();
		testPlanService.save(tp);

		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTSTEP);
		result.setAction(ResourceUploadAction.UPDATE);
		result.setId(testStep.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/testCases/{testCaseId}/name", method = RequestMethod.POST)
	public ResourceUploadStatus updateTestCaseName(HttpServletRequest request, @PathVariable("testCaseId") Long testCaseId, Principal p,
			@RequestBody TestCaseCommand tc) throws Exception {
		checkManagementSupport();
		String name = tc.getName();
		TestCase testCase = testCaseService.findOne(testCaseId);
		checkPermission(testCaseId, testCase, p);
		testCase.setName(name);
		testCaseService.save(testCase);

		TestPlan tp = testPlanService.findTestPlanContainingAbstractTestCase(testCase);
		tp.updateUpdateDate();
		testPlanService.save(tp);

		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTCASE);
		result.setAction(ResourceUploadAction.UPDATE);
		result.setId(testCase.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/testPlans/{testPlanId}/name", method = RequestMethod.POST)
	public ResourceUploadStatus updateTestPlanName(HttpServletRequest request, @PathVariable("testPlanId") Long testPlanId, Principal p,
			@RequestBody TestCaseCommand tp) throws Exception {
		checkManagementSupport();
		String name = tp.getName();
		TestPlan testPlan = testPlanService.findOne(testPlanId);
		checkPermission(testPlanId, testPlan, p);
		testPlan.setName(name);
		testPlanService.save(testPlan);
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTPLAN);
		result.setAction(ResourceUploadAction.UPDATE);
		result.setId(testPlan.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	@RequestMapping(value = "/testPlans/{testPlanId}/publish", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus publishTestPlan(HttpServletRequest request, @PathVariable("testPlanId") Long testPlanId, Principal p) throws Exception {
		return updateTestPlanScope(request, testPlanId, p, TestScope.GLOBAL);
	}

	@RequestMapping(value = "/testPlans/{testPlanId}/unpublish", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus unpublishTestPlan(HttpServletRequest request, @PathVariable("testPlanId") Long testPlanId, Principal p) throws Exception {
		return updateTestPlanScope(request, testPlanId, p, TestScope.USER);
	}

	public ResourceUploadStatus updateTestPlanScope(HttpServletRequest request, Long testPlanId, Principal p, TestScope scope) throws Exception {
		checkManagementSupport();
		TestPlan testPlan = testPlanService.findOne(testPlanId);
		checkPermission(testPlanId, testPlan, p);
		updateTestScope(testPlan, scope);
		testPlanService.save(testPlan);
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTPLAN);
		result.setAction(ResourceUploadAction.UPDATE);
		result.setId(testPlan.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	public void updateTestScope(TestPlan testPlan, TestScope scope) {
		testPlan.setScope(scope);
		Set<TestCase> testCases = testPlan.getTestCases();
		if (testCases != null) {
			for (TestCase testCase : testCases) {
				updateTestScope(testCase, scope);
			}
		}
		Set<TestCaseGroup> testCaseGroups = testPlan.getTestCaseGroups();
		if (testCaseGroups != null) {
			for (TestCaseGroup testCaseGroup : testCaseGroups) {
				updateTestScope(testCaseGroup, scope);
			}
		}
	}

	public void updateTestScope(TestCase testCase, TestScope scope) {
		testCase.setScope(scope);
		Set<TestStep> testSteps = testCase.getTestSteps();
		if (testSteps != null) {
			for (TestStep testStep : testSteps) {
				testStep.setScope(scope);
				if (testStep.getTestContext() != null) {
					testStep.getTestContext().setScope(scope);
				}
			}
		}
	}

	public void updateTestScope(TestCaseGroup testCaseGroup, TestScope scope) {
		testCaseGroup.setScope(scope);
		Set<TestCase> testCases = testCaseGroup.getTestCases();
		if (testCases != null) {
			for (TestCase testCase : testCases) {
				updateTestScope(testCase, scope);
			}
		}
		// groups can be nested; without this a published plan keeps its nested groups private
		Set<TestCaseGroup> children = testCaseGroup.getTestCaseGroups();
		if (children != null) {
			for (TestCaseGroup child : children) {
				updateTestScope(child, scope);
			}
		}
	}

	public ResourceUploadStatus deleteTestCaseGroup(TestCaseGroup testCaseGroup) throws Exception {
		checkManagementSupport();
		testCaseGroupService.delete(testCaseGroup);
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTCASEGROUP);
		result.setAction(ResourceUploadAction.DELETE);
		result.setId(testCaseGroup.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	@RequestMapping(value = "/testPlans/{testPlanId}/testCaseGroups/{testCaseGroupId}/delete", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus deleteTestCaseGroup(HttpServletRequest request, @PathVariable("testCaseGroupId") Long testCaseGroupId, Principal p,
			@PathVariable("testPlanId") Long testPlanId) throws Exception {
		checkManagementSupport();
		TestPlan tp = testPlanService.findOne(testPlanId);
		if (tp != null) {
			TestCaseGroup testCaseGroup = testCaseGroupService.findOne(testCaseGroupId);
			if (testCaseGroup != null) {
				checkPermission(testCaseGroupId, testCaseGroup, p);
				TestCaseGroup found = findTestCaseGroup(tp.getTestCaseGroups(), testCaseGroupId);
				tp.getTestCaseGroups().remove(found);
				testPlanService.save(tp);
				ResourceUploadStatus result = deleteTestCaseGroup(testCaseGroup);
				return result;
			} else {
				ResourceUploadStatus result = new ResourceUploadStatus();
				result.setType(ResourceType.TESTCASEGROUP);
				result.setAction(ResourceUploadAction.DELETE);
				result.setId(testCaseGroupId);
				result.setMessage("TestCaseCategory(" + testCaseGroupId + ") Not found");
				result.setStatus(ResourceUploadResult.FAILURE);
				return result;
			}
		} else {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setType(ResourceType.TESTCASEGROUP);
			result.setAction(ResourceUploadAction.DELETE);
			result.setId(testCaseGroupId);
			result.setMessage("TestPlan(" + testPlanId + ") Not found");
			result.setStatus(ResourceUploadResult.FAILURE);
			return result;
		}

	}

	@RequestMapping(value = "/testCaseGroups/{parentTestCaseGroupId}/testCaseGroups/{testCaseGroupId}/delete", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus deleteTestCaseGroup2(HttpServletRequest request, @PathVariable("testCaseGroupId") Long testCaseGroupId, Principal p,
			@PathVariable("parentTestCaseGroupId") Long parentTestCaseGroupId) throws Exception {
		checkManagementSupport();
		TestCaseGroup tp = testCaseGroupService.findOne(parentTestCaseGroupId);
		if (tp != null) {
			TestCaseGroup testCaseGroup = testCaseGroupService.findOne(testCaseGroupId);
			if (testCaseGroup != null) {
				checkPermission(testCaseGroupId, testCaseGroup, p);
				TestCaseGroup found = findTestCaseGroup(tp.getTestCaseGroups(), testCaseGroupId);
				tp.getTestCaseGroups().remove(found);
				testCaseGroupService.save(tp);
				ResourceUploadStatus result = deleteTestCaseGroup(testCaseGroup);
				return result;
			} else {
				ResourceUploadStatus result = new ResourceUploadStatus();
				result.setType(ResourceType.TESTCASEGROUP);
				result.setAction(ResourceUploadAction.DELETE);
				result.setId(testCaseGroupId);
				result.setMessage("TestCaseCategory(" + testCaseGroupId + ") Not found");
				result.setStatus(ResourceUploadResult.FAILURE);
				return result;
			}
		} else {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setType(ResourceType.TESTCASEGROUP);
			result.setAction(ResourceUploadAction.DELETE);
			result.setId(testCaseGroupId);
			result.setMessage("TestCaseGroup(" + parentTestCaseGroupId + ") Not found");
			result.setStatus(ResourceUploadResult.FAILURE);
			return result;
		}

	}

	private TestCaseGroup findTestCaseGroup(Set<TestCaseGroup> set, Long id) {
		for (TestCaseGroup group : set) {
			if (group.getId().equals(id)) {
				return group;
			}
		}
		return null;
	}

	private TestCase findTestCase(Set<TestCase> set, Long id) {
		for (TestCase group : set) {
			if (group.getId().equals(id)) {
				return group;
			}
		}
		return null;
	}

	private TestStep findTestStep(Set<TestStep> set, Long id) {
		for (TestStep group : set) {
			if (group.getId().equals(id)) {
				return group;
			}
		}
		return null;
	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/testCaseGroups/{testCaseGroupId}/name", method = RequestMethod.POST)
	public ResourceUploadStatus updateTestCaseGroupName(HttpServletRequest request, @PathVariable("testCaseGroupId") Long testCaseGroupId, Principal p,
			@RequestBody TestCaseCommand tcg) throws Exception {
		checkManagementSupport();
		String name = tcg.getName();
		TestCaseGroup testCaseGroup = testCaseGroupService.findOne(testCaseGroupId);
		checkPermission(testCaseGroupId, testCaseGroup, p);
		testCaseGroup.setName(name);
		testCaseGroupService.save(testCaseGroup);

		TestPlan tp = testPlanService.findTestPlanContainingAbstractTestCase(testCaseGroup);
		tp.updateUpdateDate();
		testPlanService.save(tp);

		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTCASEGROUP);
		result.setAction(ResourceUploadAction.UPDATE);
		result.setId(testCaseGroup.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/artifacts/{artifactId}", method = RequestMethod.POST, produces = "application/json", consumes = {
			"application/x-www-form-urlencoded;" })
	public ResourceUploadStatus updateArtifact(HttpServletRequest request, @PathVariable("artifactId") Long artifactId, @RequestParam("content") String content,
			Principal p, @RequestParam(name = "token", required = false) String token) throws Exception {
		// String username = null;
		String username = userIdService.getCurrentUserName(p);
		if (username == null)
			throw new NoUserFoundException("User could not be found");
		TestArtifact testArtifact = testArtifactService.findOne(artifactId);
		if (testArtifact == null) {
			throw new NoUserFoundException("Artifact not found");
		}

		if (content != null) {
			testArtifact.setHtml(content);
		}

		testArtifactService.save(testArtifact);
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTARTIFACT);
		result.setAction(ResourceUploadAction.UPDATE);
		result.setId(testArtifact.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/artifacts/{artifactId}/delete", method = RequestMethod.POST, produces = "application/json")
	public ResourceUploadStatus deleteArtifact(HttpServletRequest request, @PathVariable("artifactId") Long artifactId, Principal p) throws Exception {
		checkManagementSupport();
		// String username = null;
		String username = userIdService.getCurrentUserName(p);
		if (username == null)
			throw new NoUserFoundException("User could not be found");
		TestArtifact testArtifact = testArtifactService.findOne(artifactId);
		if (testArtifact == null) {
			throw new NoUserFoundException("Artifact not found");
		}
		testArtifactService.delete(testArtifact);
		ResourceUploadStatus result = new ResourceUploadStatus();
		result.setType(ResourceType.TESTARTIFACT);
		result.setAction(ResourceUploadAction.DELETE);
		result.setId(testArtifact.getId());
		result.setStatus(ResourceUploadResult.SUCCESS);
		return result;
	}

	/**
	 * Uploads zip file and stores it in a temporary directory
	 * 
	 * @param request Client request
	 * @param part    Zip file
	 * @param p       Principal
	 * @return a token or some errors
	 * @throws MessageUploadException
	 */
	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/uploadZip", method = RequestMethod.POST, consumes = { "multipart/form-data" })
	@ResponseBody
	@Transactional(value = "transactionManager")
	public ResourceUploadStatus uploadZip(ServletRequest request, @RequestPart("file") MultipartFile part, Principal p, Authentication u)
			throws MessageUploadException {
		try {
			Map<String, Object> resultMap = new HashMap<String, Object>();

			checkManagementSupport();
			String filename = part.getOriginalFilename();
			String extension = filename.substring(filename.lastIndexOf(".") + 1);
			if (!extension.equalsIgnoreCase("zip")) {
				throw new MessageUploadException("Unsupported content type. Supported content types are: '.zip' ");
			}

			String username = userIdService.getCurrentUserName(p);
			if (username == null)
				throw new NoUserFoundException("User could not be found");
			String token = UUID.randomUUID().toString().replaceAll("-","_");
			filename = part.getOriginalFilename().substring(0, part.getOriginalFilename().lastIndexOf("."));

			String directory = bundleHandler.unzip(part.getBytes(), CB_RESOURCE_BUNDLE_DIR + "/" + token);

			Set<File> files = bundleHandler.findFiles(directory, "TestPlan.json");
			for (File f : files) {
				String testplanContent = FileUtils.readFileToString(f);
				ObjectMapper mapper = new ObjectMapper();
				JsonNode testCasesObj = mapper.readTree(testplanContent);
				Long id = testCasesObj.get("id").asLong();
				TestPlan tp = testPlanService.findByPersistentId(id);
				if (tp != null && !tp.getAuthorUsername().equalsIgnoreCase(username)) {
					FileUtils.deleteDirectory(new File(directory));
					ResourceUploadStatus result = new ResourceUploadStatus();
					result.setAction(ResourceUploadAction.UPLOAD);
					result.setStatus(ResourceUploadResult.FAILURE);
					result.setMessage("A different test plan with the same identifier already exists and belongs to a different user");
					return result;
				}
			}
			
			//files validation 		
			List<ProfileValidationReport> reports = fileValidationHandler.getHTMLValidatioReportForContextBased(directory);
			List<String> filteredreports = reports.stream()
	                .filter(report -> !report.isSuccess())
	                .map(ProfileValidationReport::generateHTML)
	                .collect(Collectors.toList());
			
			 if (filteredreports.size()>0) {
				 	ResourceUploadStatus result = new ResourceUploadStatus();
					result.setAction(ResourceUploadAction.UPLOAD);
					result.setStatus(ResourceUploadResult.FAILURE);
					result.setMessage("File validation failed");
					result.setReports(filteredreports);
		            logger.info("Uploaded profile file with errors " + part.getName());
		            FileUtils.deleteDirectory(new File(directory));
					return result;		            
		          } else {
		        	ResourceUploadStatus result = new ResourceUploadStatus();
		  			result.setAction(ResourceUploadAction.UPLOAD);
		  			result.setStatus(ResourceUploadResult.SUCCESS);
		  			result.setToken(token);
		            logger.info("Uploaded valid zip File file " + part.getName());
		  			return result;

		       }
			 			 
			
//			// testPlanService.findOne()
//
//			ResourceUploadStatus result = new ResourceUploadStatus();
//			result.setAction(ResourceUploadAction.UPLOAD);
//			result.setStatus(ResourceUploadResult.SUCCESS);
//			result.setToken(token);
//			return result;
		
		} catch (NoUserFoundException e) {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setAction(ResourceUploadAction.UPLOAD);
			result.setStatus(ResourceUploadResult.FAILURE);
			result.setMessage(e.getMessage());
			return result;

		} catch (MessageUploadException e) {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setAction(ResourceUploadAction.UPLOAD);
			result.setStatus(ResourceUploadResult.FAILURE);
			result.setMessage(e.getMessage());
			return result;

		} catch (Exception e) {
			e.printStackTrace();
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setAction(ResourceUploadAction.UPLOAD);
			result.setStatus(ResourceUploadResult.FAILURE);
			result.setMessage(e.getMessage());
			return result;
		}

	}

	/**
	 * Uploads zip file and stores it in a temporary directory
	 * 
	 * @param request Client request
	 * @param part    Zip file
	 * @param p       Principal
	 * @return a token or some errors
	 * @throws MessageUploadException
	 */
	@PreAuthorize("hasRole('tester')")
	@RequestMapping(value = "/saveZip", method = RequestMethod.POST, produces = "application/json")
	@ResponseBody
	@Transactional(value = "transactionManager")
	public ResourceUploadStatus saveZip(ServletRequest request, Principal p, @RequestBody Map<String, Object> params, Authentication u)
			throws MessageUploadException {
		boolean isAdmin = false;
		try {

			String token = params.get("token").toString();
			String domain = params.get("domain").toString();
			// TODO: Check nullity

			String username = userIdService.getCurrentUserName(p);
			isAdmin = userService.isAdmin(username);
			String directory = CB_RESOURCE_BUNDLE_DIR + "/" + token;

			// ADD globals
			if (Files.exists(Paths.get(directory + "/Global/Profiles"))) {
				resourceLoader.addOrReplaceIntegrationProfile(directory + "/Global/Profiles/", domain, TestScope.USER, u.getName(), false);
			}
			if (Files.exists(Paths.get(directory + "/Global/Constraints/"))) {
				resourceLoader.addOrReplaceConstraints(directory + "/Global/Constraints/", domain, TestScope.USER, u.getName(), false);
			}
			if (Files.exists(Paths.get(directory + "/Global/Tables/"))) {
				resourceLoader.addOrReplaceValueSet(directory + "/Global/Tables/", domain, TestScope.USER, u.getName(), false);
			}
			if (Files.exists(Paths.get(directory + "/Global/CoConstraints/"))) {
				resourceLoader.addOrReplaceCoConstraints(directory + "/Global/CoConstraints/", domain, TestScope.USER, u.getName(), false);
			}
			if (Files.exists(Paths.get(directory + "/Global/Bindings/"))) {
				resourceLoader.addOrReplaceValueSetBindings(directory + "/Global/Bindings/", domain, TestScope.USER, u.getName(), false);
			}
			if (Files.exists(Paths.get(directory + "/Global/Slicings/"))) {
				resourceLoader.addOrReplaceSlicings(directory + "/Global/Slicings/", domain, TestScope.USER, u.getName(), false);
			}

			List<TestPlan> plans = resourceLoader.createTP(directory + "/Contextbased/", domain, TestScope.USER, u.getName(), false);
			TestPlan tp = plans.get(0);
			updateToUser(tp, TestScope.USER, username);
			ResourceUploadStatus result = resourceLoader.handleTP(tp);
			result.setId(tp.getId());
			FileUtils.deleteDirectory(new File(directory));
			return result;			

		} catch (NoUserFoundException e) {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setAction(ResourceUploadAction.ADD);
			result.setStatus(ResourceUploadResult.FAILURE);
			result.setMessage(e.getMessage());
			return result;

		} catch (MessageUploadException e) {
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setAction(ResourceUploadAction.ADD);
			result.setStatus(ResourceUploadResult.FAILURE);
			result.setMessage(e.getMessage());
			return result;

		} catch (Exception e) {
			e.printStackTrace();
			ResourceUploadStatus result = new ResourceUploadStatus();
			result.setAction(ResourceUploadAction.ADD);
			result.setStatus(ResourceUploadResult.FAILURE);
			if (isAdmin) {
				result.setMessage(e.getMessage());
			} else {
				result.setMessage("An exception occured");
			}

			return result;
		}
	}
	
	@PreAuthorize("hasRole('tester')")
	  @RequestMapping(value = "/testPlans/{testPlanId}/refreshTestContext", method = RequestMethod.POST)
	  @ResponseBody
	  public Map<String, Object> refreshTestPlanTestContextModels(HttpServletRequest request,
	      @PathVariable("testPlanId") Long testPlanId,
	      Authentication auth) {
	    
	        Map<String, Object> resultMap = new HashMap<String, Object>();
	      try {
			checkManagementSupport();
		
	      TestPlan testPlan = testPlanService.findOne(testPlanId);
	      if (testPlan == null) {
	        throw new Exception("Test Plan could not be found");
	      }
	      String username = auth.getName();
	      if (!username.equals(testPlan.getAuthorUsername()) && !userService.isAdmin(username)) {
	        throw new Exception("You do not have sufficient right to change this profile group");
	      }
	        
	      resourceLoader.updateCBTestPlanConformanceProfileJson(testPlan);
	      testPlanService.save(testPlan);

	      return resultMap;
	      } catch (Exception e) {
	       resultMap.put("error", e.getMessage());
	       return resultMap;
	  	}
	  }
	

	private void checkPermission(Long id, AbstractTestCase testObject, Principal p) throws Exception {
		String username = userIdService.getCurrentUserName(p);
		if (username == null)
			throw new NoUserFoundException("User could not be found");
		if (testObject == null)
			throw new Exception("No " + testObject.getType() + " (" + id + ") found");
		TestScope scope = testObject.getScope();
		if (scope.equals(TestScope.GLOBAL) && !userService.hasGlobalAuthorities(username)) {
			throw new NoUserFoundException("You do not have the permission to perform this task");
		}
		if (!username.equals(testObject.getAuthorUsername()) && !userService.isAdmin(username)) {
			throw new NoUserFoundException("You do not have the permission to perform this task");
		}
	}

	private void checkManagementSupport() throws Exception {
		if (!appInfoService.get().isCbManagementSupported()) {
			throw new Exception("This operation is not supported by this tool");
		}
	}

	private void updateToUser(TestPlan testPlan, TestScope scope, String username) {
		testPlan.setScope(scope);
		testPlan.setAuthorUsername(username);

		for (TestCaseGroup group : testPlan.getTestCaseGroups()) {
			updateToUser(group, scope, username);

		}

		for (TestCase testCase : testPlan.getTestCases()) {
			updateToUser(testCase, scope, username);
		}

	}

	private void updateToUser(TestCaseGroup testCaseGroup, TestScope scope, String username) {
		testCaseGroup.setScope(scope);
		testCaseGroup.setAuthorUsername(username);
		for (TestCaseGroup group : testCaseGroup.getTestCaseGroups()) {
			updateToUser(group, scope, username);
		}
		for (TestCase testCase : testCaseGroup.getTestCases()) {
			updateToUser(testCase, scope, username);
		}
	}

	private void updateToUser(TestCase testCase, TestScope scope, String username) {
		testCase.setScope(scope);
		testCase.setAuthorUsername(username);
		for (TestStep step : testCase.getTestSteps()) {
			step.setScope(scope);
			step.setAuthorUsername(username);
		}
	}

	private void updateDateOfParentTestPlan(TestCaseGroup tcg) {
		List<TestPlan> list = testPlanService.findShortAllByStageAndDomain(tcg.getStage(), tcg.getDomain());

	}

}
