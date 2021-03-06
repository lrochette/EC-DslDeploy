package com.electriccloud.plugin.spec
import spock.lang.*

class victor extends PluginTestHelper {
  static String pName='EC-DslDeploy'
  @Shared String pVersion
  @Shared String plugDir
  static String projName="SAMPLE_VAL"

  def doSetupSpec() {
    pVersion = getP("/plugins/$pName/pluginVersion")
    plugDir = getP("/server/settings/pluginsDirectory")
    dsl """
      deleteProject(projectName: "$projName")
      deleteProject(projectName: "POST_VICTOR")
      deleteResource(resourceName: "res457")
    """
  }

  def doCleanupSpec() {
    conditionallyDeleteProject(projName)
    conditionallyDeleteProject("POST_VICTOR")
    dsl """
      deleteResource(resourceName: "res457")
    """
  }

  // Check sample
  def "victor test suite upload"() {
    given: "the victor code"
    when: "Load DSL Code"
      def p= runProcedureDsl("""
        runProcedure(
          projectName: "/plugins/$pName/project",
          procedureName: "installDslFromDirectory",
          actualParameter: [
            directory: "$plugDir/$pName-$pVersion/lib/dslCode/victor",
            pool: 'default'
          ]
        )""")
    then: "job succeeds"
      assert p.jobId
      assert getJobProperty("outcome", p.jobId) == "success"

    // check project property exists
    when: "checking project properties"
      def prop1=getP("/projects/$projName/projectProperty")
      def prop2=getP("/projects/$projName/prop1")
    then: "project properties are found"
      assert prop1  == "123"    // from project.groovy
      assert  prop2 =~ /Hello world\s+/    // from properties/

    // check application is found
    then: "application is found"
      def app=dsl """
        getApplication(
          projectName: "$projName",
          applicationName: "testApp"
        )"""
      assert app.application.applicationName == "testApp"

    // Check for dashboard
    then: "dashboard is found"
      def dash= dsl """
        getDashboard(
          projectName: "$projName",
          dashboardName: 'testDashboard',
        )"""
      assert dash.dashboard.dashboardName == "testDashboard"
      assert dash.dashboard.description   == "val"
    // Check for reportingFilter
    then: "reportingFilter is found"
      def rf= dsl """
        getReportingFilter(
          projectName: "$projName",
          dashboardName: 'testDashboard',
          reportingFilterName: 'DateFilter'
        )"""
      assert rf.reportingFilter.reportingFilterName == "DateFilter"
      assert rf.reportingFilter.type                == "DATE"
    // Check for widget
    then: "widget is found"
      def w= dsl """
        getWidget(
          projectName: "$projName",
          dashboardName: 'testDashboard',
          widgetName: 'testWidget'
        )"""
      assert w.widget.widgetName == "testWidget"

    // check environment is found
    then: "environment is found"
      def env=dsl """
        getEnvironment(
          projectName: "$projName",
          environmentName: "testEnv"
        )"""
      assert env.environment.environmentName == "testEnv"
      assert env.environment.description     == "val"
    // check environmentTier is found
    then: "environmentTier is found"
      def envT=dsl """
        getEnvironmentTier(
          projectName: "$projName",
          environmentName: "testEnv",
          environmentTierName: "Tier 1",
        )"""
      assert envT.environmentTier.environmentTierName == "Tier 1"
    // Check for cluster
    then: "cluster is found"
      def cl = dsl """
        getCluster(
          projectName: "$projName",
          environmentName: "testEnv",
          clusterName: 'testCluster',
        )"""
      assert cl.cluster.clusterName == "testCluster"
      assert cl.cluster.description == "val"

    // check pipeline is found
    then: "pipeline is found"
      def pipe=dsl """
        getPipeline(
          projectName: "$projName",
          pipelineName: "testPipeline"
        )"""
      assert pipe.pipeline.pipelineName == "testPipeline"
    // Check task exists
    then: "task is found"
      def task=dsl """
        getTask(
          projectName: "$projName",
          pipelineName: "testPipeline",
          stageName: 'UAT',
          taskName: 'JA1 Deploy'
        )"""
      assert task.task.taskName == "JA1 Deploy"
      assert task.task.environmentName == "UAT"

    // check procedure is found
    then: "procedure is found"
      def proc=dsl """
        getProcedure(
          projectName: "$projName",
          procedureName: "testProcedure"
        )"""
      assert proc.procedure.procedureName == "testProcedure"
    // check step is found
    then: "step is found"
      def st=dsl """
        getStep(
          projectName: "$projName",
          procedureName: "testProcedure",
          stepName: 'echo'
        )"""
      assert st.step.shell == "ec-perl"

    // check release is found
    then: "release is found"
      def rel=dsl """
        getRelease(
          projectName: "$projName",
          releaseName: "testRelease"
        )"""
      assert rel.release.releaseName == "testRelease"
      assert rel.release.plannedEndTime =~ /2019-04-04T/

    // check report is found
    then: "report is found"
      def rep=dsl """
        getReport(
          projectName: "$projName",
          reportName: "testReport"
        )"""
      assert rep.report.reportName == "testReport"
      assert rep.report.reportObjectTypeName == 'job'

    // check resource is found
    then: "resource is found"
      def rsc=dsl """
        getResource(
          resourceName: "res457"
        )"""
      assert rsc.resource.resourceName == "res457"
      assert rsc.resource.hostName == 'doesnotexist'
      assert getP("/resources/res457/prop1") =~ /val23456\s+/
    // check service is found
    then: "service is found"
      def serv=dsl """
        getService(
          projectName: "$projName",
          serviceName: "testService"
        )"""
      assert serv.service.serviceName == "testService"

    // Issue #10 - POST
    then: "POST project is found"
      def pp=dsl """ getProject(projectName: "POST_VICTOR") """
      assert pp.project.projectName == "POST_VICTOR"
   }
}
