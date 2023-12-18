package org.rundeck.tests.functional.selenium

import org.openqa.selenium.By
import org.rundeck.tests.functional.selenium.pages.*
import org.rundeck.util.annotations.SeleniumCoreTest
import org.rundeck.util.container.SeleniumBase

@SeleniumCoreTest
class LogViewerOutput extends SeleniumBase{

    def setupSpec(){
        setupProject("AutoFollowTest")
    }

    def "auto scroll on log viewer page show last input"() {

        setup:
        LoginPage loginPage = page LoginPage
        SideBar sideBar = page SideBar
        JobsListPage jobListPage = page JobsListPage
        JobCreatePage jobCreatePage = page JobCreatePage
        ProjectHomePage projectHomePage = page ProjectHomePage
        JobShowPage jobShowPage = page JobShowPage

        when:
        loginPage.go()
        loginPage.login(TEST_USER, TEST_PASS)
        page(ProjectListPage).waitUntilPageLoaded()
        projectHomePage.goProjectHome("AutoFollowTest")
        sideBar.goTo(SideBarNavLinks.JOBS).click()
        jobListPage.getCreateJobLink().click()
        jobCreatePage.getJobNameField().sendKeys("loop job")
        jobCreatePage.getTab(JobTab.WORKFLOW).click()
        jobCreatePage.getStepByType(StepName.COMMAND, StepType.NODE).click()
        jobCreatePage.waitForStepToBeShown(By.name("pluginConfig.adhocRemoteString"))
        jobCreatePage.el(By.name("pluginConfig.adhocRemoteString")).sendKeys("for i in {1..60}; do echo NUMBER \$i; sleep 0.2; done")
        jobCreatePage.getSaveStepButton().click()
        jobCreatePage.waitForSavedStep(0)
        jobCreatePage.getCreateButton().click()
        jobShowPage.getRunJobBtn().click()
        jobShowPage.getLogOutputBtn().click()
        jobShowPage.waitForLogOutput(By.xpath("//span[contains(text(),'NUMBER ')]"),3,5)
        def firstLocation = jobShowPage.el(By.xpath("//span[contains(text(),'NUMBER 1')]")).getLocation()
        jobShowPage.waitForLogOutput(By.xpath("//span[contains(text(),'NUMBER ')]"),35,40)
        def finalLocation = jobShowPage.el(By.xpath("//span[contains(text(),'NUMBER 1')]")).getLocation()

        then:
        firstLocation != finalLocation

    }

}
