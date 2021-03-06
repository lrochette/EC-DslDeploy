/* ###########################################################################
#
#  BaseProject: extension of BasePlugin to allow for additional Flow objects
#
#  Author: L.Rochette
#
#  Copyright 2017-2019 Electric-Cloud Inc.
#
#     Licensed under the Apache License, Version 2.0 (the "License");
#     you may not use this file except in compliance with the License.
#     You may obtain a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
#     Unless required by applicable law or agreed to in writing, software
#     distributed under the License is distributed on an "AS IS" BASIS,
#     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#     See the License for the specific language governing permissions and
#     limitations under the License.
#
# History
# ---------------------------------------------------------------------------
# 2019-03-26 lrochette  Fix #11 to show skipped files
############################################################################ */
package com.electriccloud.commander.dsl.util

import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.util.XmlSlurper
import java.io.File

import org.codehaus.groovy.control.CompilerConfiguration
import com.electriccloud.commander.dsl.DslDelegate
import com.electriccloud.commander.dsl.DslDelegatingScript

abstract class BaseProject extends DslDelegatingScript {
  // return the object.groovy or object.dsl
  //    AKA project.groovy, procedure.dsl, pipeline.groovy, ...
  // Show ignored files to make it easier to debug when a badly named file is
  // skiped

  File getObjectDSLFile(File objDir, String obj) {
    // println "Checking $obj in ${objDir.name}"
    File found=null
    objDir.eachFileMatch(FileType.FILES, ~/(?i)^.*\.(groovy|dsl)/) { dslFile ->
      // println "Processing ${dslFile.name}"
      if (dslFile.name ==~ /(?i)${obj}\.(groovy|dsl)/) {
        if (found) {
          println "Multiple files match the ${obj}.groovy or ${obj}.dsl"
          setProperty(propertyName: "outcome", value: "warning")
        }
        found =  dslFile
      }  else {
         println "Ignoring incorrect file  ${dslFile.name} in ${objDir.name}"
         setProperty(propertyName: "outcome", value: "warning")
      }
    }
    return found
  }

  boolean isDslFile(File dslFile) {
    def fileName=dslFile.name

    switch(dslFile.name) {
      case ~/(?i)\.groovy$/:
        return true
       case ~/(?i)\.dsl$/:
         return true
       default:
        return false
      } // switch
  }

 def loadResource(String resourceDir, String resourceName) {
   // load the resource.groovy if it exists
   File dslFile=getObjectDSLFile(new File(resourceDir), "resource");
   // println "projet returned " + dslFile.absolutePath
   if (dslFile) {
     println "Processing resource file resources/$resourceName/${dslFile.name}"
     def res=evalInlineDsl(dslFile.toString(), [resourceName: resourceName, resourceDir: resourceDir])
   }
 }

  def loadResourceProperties(String resourceDir, String resourceName) {
    // Recursively navigate each file or sub-directory in the properties directory
    //Create a property corresponding to a file,
    // or create a property sheet for a sub-directory before navigating into it
    def resPropertyDir=new File(resourceDir, 'properties')
    if (resPropertyDir.directory) {
      loadNestedProperties("/resources/$resourceName", resPropertyDir)
    }  else {
      println "No properties directory for resource $resourceName"
    }
  }

  def loadProject(String projectDir, String projectName) {
    // load the project.groovy if it exists
    File dslFile=getObjectDSLFile(new File(projectDir), "project");
    // println "projet returned " + dslFile.absolutePath
    if (dslFile?.exists()) {
      println "Processing project file projects/$projectName/${dslFile.name}"
      def proj=evalInlineDsl(dslFile.toString(), [projectName: projectName, projectDir: projectDir])
    }
  }

  def loadProjectProperties(String projectDir, String projectName) {
    // Recursively navigate each file or sub-directory in the properties directory
    //Create a property corresponding to a file,
    // or create a property sheet for a sub-directory before navigating into it
    def projPropertyDir=new File(projectDir, 'properties')
    if (projPropertyDir.directory) {
      loadNestedProperties("/projects/$projectName", projPropertyDir)
    }  else {
      println "No properties directory for project $projectName"
    }
  }

  def loadNestedProperties(String propRoot, File propsDir) {
    // println "Entering loadNestedProperties($propRoot," +  propsDir.toString() + ")"
    propsDir.eachFile { dir ->
      println "  parsing " + dir.toString()
      int extension = dir.name.lastIndexOf('.')
      int endIndex = extension > -1 ? extension : dir.name.length()
      String propName = dir.name.substring(0, endIndex)
      String propPath = "${propRoot}/${propName}"
      if (dir.directory) {
        property propName, {
          loadNestedProperties(propPath, dir)
        }
      } else {
        def exists = getProperty(propPath, suppressNoSuchPropertyException: true, expand: false)
        if (exists) {
          modifyProperty propertyName: propPath, value: dir.text
        } else {
          createProperty propertyName: propPath, value: dir.text
        }
      }
    }
  }

  def loadProcedure(String projectDir, String projectName, String dslFile) {
    // println "    Entering loadProcedure($projectDir, $projectName, $dslFile)"
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadProcedures(String projectDir, String projectName) {
    // Loop over the sub-directories in the procedures directory
    // and evaluate procedures if a procedure.dsl file exists
    // println "Entering loadProcedures($projectDir, $projectName)"
    def counter=0
    File dir = new File(projectDir, 'procedures')
    if (dir.exists()) {
      dir.eachDir {
        File procDslFile = getObjectDSLFile(it, "procedure")
        if (procDslFile?.exists()) {
          println "Processing procedure DSL file ${procDslFile.absolutePath}"
          def proc = loadProcedure(projectDir, projectName, procDslFile.absolutePath)
          counter++
          //create formal parameters using form.xml
          File formXml = new File(it, 'form.xml')
          if (formXml.exists()) {
            println "Processing form XML $formXml.absolutePath"
            buildFormalParametersFromFormXmlToProcedure(proc, formXml)
          }
        }
      }
    }
    else {
      println "No 'procedures' directory for project '$projectName'"
    }
    return counter
  }

  def loadPipeline(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadPipelines(String projectDir, String projectName) {
    // Loop over the sub-directories in the pipelines directory
    // and evaluate pipelines if a pipeline.dsl file exists
    def counter=0
    def errorCode=0
    File pipesDir = new File(projectDir, 'pipelines')
    if (pipesDir.exists()) {
      // sort pipelines alphabetically
      def dlist=[]
      pipesDir.eachDir {dlist << it}
      dlist.sort({it.name})
      for (fdir in dlist) {
        def pipeName=fdir.name
        File dslFile = getObjectDSLFile(fdir, "pipeline")
        if (dslFile?.exists()) {
          println "Processing pipeline file projects/$projectName/pipelines/$pipeName/${dslFile.name}"
          def pipe = loadPipeline(projectDir, projectName, dslFile.absolutePath)
          // transform single result in list or keep list
          boolean isList=pipe instanceof List
          def pList=[]
          def rightType = true
          if (isList) {
            pList=pipe
            // Check if the response is of expected type
            rightType = isTypeOrListOfType (pipe, Pipeline.class)
            println "Pipeline response is right type (Pipeline)? $rightType"
            if (! rightType) {
              println "  incorrect type return from ${dslFile.absolutePath}"
              errorCode = -1
            }
          } else {
            pList << pipe
          }
            // Process List only if we have pipeline
          if (rightType) {
            pList.each {
              counter++
              //create formal parameters using form.xml
              File formXml = new File(fdir, 'form.xml')
              if (formXml.exists()) {
                println "Processing form XML $formXml.absolutePath"
                buildFormalParametersFromFormXmlToPipeline(it, formXml)
              }
            }
          }
        }     // pipeline.groovy exists
      }       // loop on pipeline directories
    }
    return errorCode == -1? -1 : counter
  }

  def isTypeOrListOfType(def obj, def type) {
     // printing for purely debugging  purposes
     // obj instanceof List ? obj.each { println ("Item type ${it.class.name}") } : println ("Obj type ${obj.class.name}")
     obj instanceof List ? obj.every { type.isInstance(it)} : type.isInstance(obj)
  }

  // ########################################################################
  //
  // Services
  //
  // ########################################################################
  def loadService(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadServices(String projectDir, String projectName) {
    // Loop over the sub-directories in the microservices directory
    // and evaluate services if a service.dsl file exists
    def counter=0
    File dir = new File(projectDir, 'services')
    if (dir.exists()) {
      dir.eachDir {
        def servName=it.name
        File dslFile = getObjectDSLFile(it, "service")
        if (dslFile?.exists()) {
          println "Processing service  file projects/$projectName/services/$servName/${dslFile.name}"
          def pipe = loadService(projectDir, projectName, dslFile.absolutePath)
          counter++
        }
      }
    }
    return counter
  }

  // ########################################################################
  //
  // Cluster
  //
  // ########################################################################
  def loadCluster(String projectDir,  String environmentDir,
                  String projectName, String environmentName, String dslFile) {
    environment environmentName, {
      return evalInlineDsl(dslFile, [
                          projectName: projectName,
                          environmentName: environmentName,
                          projectDir: projectDir,
                          environmentDir: environmentDir])
    }
  }

  def loadClusters(String projectDir,  String environmentDir,
                   String projectName, String environmentName) {

    // Loop over the groovy files in the clusters directory
    // as there are no child objects in cluster
    def counter=0
    File dir = new File(environmentDir, 'clusters')
    if (dir.exists()) {
      // println "  directory clusters exists"
      dir.eachFile {
        def clusterName=it.name
        File dslFile = getObjectDSLFile(it, "cluster")
        if (dslFile) {
          println "    Processing cluster file projects/$projectName/environments/$environmentName/clusters/$clusterName/${dslFile.name}"
          loadCluster(projectDir,  environmentDir,
                      projectName, environmentName, dslFile.absolutePath)
          counter++
        }   // .dsl or .groovy file
      }     // file loop
    }       // directory clusters exist
    return counter
  }

  // ########################################################################
  //
  // Environments
  //
  // ########################################################################
  def loadEnvironment(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }
  def loadEnvironments(String projectDir, String projectName) {
    // Loop over the sub-directories in the environments directory
    // and evaluate services if a service.dsl file exists
    // println "Entering loadEnvironments for $projectDir ($projectName)"
    def envCounter=0
    def clusterCounter=0
    File dir = new File(projectDir, 'environments')
    if (dir.exists()) {
      dir.eachDir {
        def environmentName=it.name
        def environmentDir=it.absolutePath
        File dslFile = getObjectDSLFile(it, "environment")
        if (dslFile?.exists()) {
          println "Processing environment file projects/$projectName/environments/$environmentName/${dslFile.name}"
          def pipe = loadEnvironment(projectDir, projectName, dslFile.absolutePath)
          envCounter++
        }
        // loop over clusters
        clusterCounter += loadClusters(projectDir, environmentDir, projectName, environmentName)
      }
    }
    return [envCounter, clusterCounter]
  }

  // ########################################################################
  //
  // Releases
  //
 // ########################################################################
  def loadRelease(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }
  def loadReleases(String projectDir, String projectName) {
    // Loop over the sub-directories in the releases directory
    // and evaluate reelases if a release.dsl file exists
    def counter=0
    File dir = new File(projectDir, 'releases')
    if (dir.exists()) {
      //println "directory releases exists"
      dir.eachDir {
        def releaseName=it.name
        File dslFile = getObjectDSLFile(it, "release")
        if (dslFile?.exists()) {
          println "Processing release file projects/$projectName/releases/$releaseName/${dslFile.name}"
          def rel = loadRelease(projectDir, projectName, dslFile.absolutePath)
          counter++
        }
      }  // eachDir loop
    }    // directory releases exist
    return counter
  }

   // ########################################################################
   //
   // catalogItems
   //
  // ########################################################################
  def loadCatalogItem(String projectDir,  String catalogDir,
                      String projectName, String catalogName,
                      String dslFile) {
    catalog catalogName, {
      evalInlineDsl(dslFile, [
                          projectName: projectName,
                          catalogName: catalogName,
                          projectDir: projectDir,
                          catalogDir: catalogDir])
    }
  }

  def loadCatalogItems(String projectDir,  String catalogDir,
                       String projectName, String catalogName) {
    // Loop over the sub-directories in the catalogItems directory
    // and evaluate catalogItems if a catalogItem.dsl file exists
    def counter=0
    File dir = new File(catalogDir, 'catalogItems')
    if (dir.exists()) {
      // println "  directory catalogItems exists"
      dir.eachDir {
        def itemName=it.name
        File dslFile = getObjectDSLFile(it, "catalogItem")
        if (dslFile?.exists()) {
          println "  Processing catalogItem file projects/$projectName/catalogs/$catalogName/catalogItems/$itemName/${dslFile.name}"
          def item = loadCatalogItem(projectDir, catalogDir,
                                     projectName, catalogName, dslFile.absolutePath)
          counter++
        }
      }  // eachDir loop
    }    // directory catalogItems exist
    return counter
  }

  // ########################################################################
  //
  // Catalogs
  //
  // ########################################################################
  def loadCatalog(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadCatalogs(String projectDir, String projectName) {
    // Loop over the sub-directories in the catalogs directory
    // and evaluate catalogs if a catalog.dsl file exists
    def catCounter=0
    def itemCounter=0;
    File dir = new File(projectDir, 'catalogs')
    if (dir.exists()) {
      dir.eachDir {
        def catalogName=it.name
        def catalogDir=it.absolutePath
        File dslFile = getObjectDSLFile(it, "catalog")
        if (dslFile?.exists()) {
          println "Processing catalog file projects/$projectName/catalogs/$catalogName/${dslFile.name}"
          def cat = loadCatalog(projectDir, projectName, dslFile.absolutePath)
          catCounter++

        }
        // load catalogitems
        itemCounter += loadCatalogItems(projectDir,  catalogDir,
                                        projectName, catalogName)
      }  // eachDir loop
    }    // directory catalogs exist
    return [catCounter, itemCounter]
  }
  // ########################################################################
  //
  // Widgets
  //
  // ########################################################################
  def loadWidget(String projectDir,  String dashboardDir,
                 String projectName, String dashboardName, String dslFile) {
    dashboard dashboardName, {
      evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
    }
  }

  def loadWidgets(String projectDir,  String dashboardDir,
                  String projectName, String dashboardName) {
    // Loop over the sub-directories in the widgets directory
    // and evaluate widgets if a widget.dsl file exists
    def counter=0
    File dir = new File(dashboardDir, 'widgets')
    if (dir.exists()) {
      dir.eachDir {
        def widgetName=it.name
        File dslFile = getObjectDSLFile(it, "widget")
        if (dslFile?.exists()) {
          println "  Processing widget file projects/$projectName/dashboards/$dashboardName/widgets/$widgetName/${dslFile.name}"
          def cat = loadWidget(projectDir, dashboardDir, projectName, dashboardName, dslFile.absolutePath)
          counter++
        }
      }  // eachDir loop
    }    // directory widgets exist
    return counter
  }

  // ########################################################################
  //
  // Dashboards
  //
  // ########################################################################
  def loadDashboard(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadDashboards(String projectDir, String projectName) {
    // Loop over the sub-directories in the dashboards directory
    // and evaluate dashboards if a dashboard.dsl file exists
    def dashCounter=0
    def widgetCounter=0
    File dir = new File(projectDir, 'dashboards')
    if (dir.exists()) {
      dir.eachDir {
        def dashboardName=it.name
        def dashboardDir=it.absolutePath
        File dslFile = getObjectDSLFile(it, "dashboard")
        if (dslFile?.exists()) {
          println "Processing dashboard file projects/$projectName/dashboards/$dashboardName/${dslFile.name}"
          def cat = loadDashboard(projectDir, projectName, dslFile.absolutePath)
          dashCounter++

        }
        // Load widgets
        widgetCounter += loadWidgets(projectDir,  dashboardDir,
                                     projectName, dashboardName)

      }  // eachDir loop
    }    // directory dashboards exist
    return [dashCounter, widgetCounter]
  }

  // ########################################################################
  //
  // Reports
  //
  // ########################################################################
  def loadReport(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadReports(String projectDir, String projectName) {
    // Loop over the sub-directories in the reports directory
    // and evaluate reports if a report.dsl file exists
    def counter=0
    File dir = new File(projectDir, 'reports')
    if (dir.exists()) {
      dir.eachDir {
        def reportName=it.name
        File dslFile = getObjectDSLFile(it, "report")
        if (dslFile) {
          println "Processing report file projects/$projectName/reports/$reportName/${dslFile.name}"
          def pipe = loadReport(projectDir, projectName, dslFile.absolutePath)
          counter++
        }   // report.dsl exists
      }     // report sub-dir loop
    }       // "reports" exists
    return counter
  }

  def loadComponent(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadComponents(String projectDir, String projectName) {
    // Loop over the sub-directories in the components directory
    // and evaluate componentTemplate if a component.dsl file exists
    def counter=0
    File dir = new File(projectDir, 'components')
    if (dir.exists()) {
      //println "directory releases exists"
      dir.eachDir {
        File dslFile = getObjectDSLFile(it, "component")
        if (dslFile?.exists()) {
          println "Processing component template DSL file ${dslFile.absolutePath}"
          def app = loadComponent(projectDir, projectName, dslFile.absolutePath)
          counter++
        }
      }  // eachDir loop
    }    // directory components exist
    return counter
  }


  def loadApplication(String projectDir, String projectName, String dslFile) {
    return evalInlineDsl(dslFile, [projectName: projectName, projectDir: projectDir])
  }

  def loadApplications(String projectDir, String projectName) {
    // Loop over the sub-directories in the applications directory
    // and evaluate application if a application.dsl file exists
    def counter=0
    File dir = new File(projectDir, 'applications')
    if (dir.exists()) {
      //println "directory applications exists"
      dir.eachDir {
        def appName=it.name
        File dslFile = getObjectDSLFile(it, "application")
        if (dslFile?.exists()) {
          println "Processing application file projects/$projectName/applications/$appName/${dslFile.name}"
          def app = loadApplication(projectDir, projectName, dslFile.absolutePath)
          counter++
        }
      }  // eachDir loop
    }    // directory applications exist
    return counter
  }

  //Helper function to load another dsl script and evaluate it in-context
  def evalInlineDsl(String dslFile, Map bindingMap) {
    // println "evalInlineDsl: $dslFile"
    // println "  Map: " + bindingMap
    CompilerConfiguration cc = new CompilerConfiguration();
    cc.setScriptBaseClass(InnerDelegatingScript.class.getName());
    //println "Class loader class name: ${this.scriptClassLoader.class.name}"
    // NMB-27865: Use the same groovy class loader that was used for evaluating
    // the DSL passed to evalDsl.
    GroovyShell sh = new GroovyShell(this.scriptClassLoader, bindingMap? new Binding(bindingMap) : new Binding(), cc);
    DelegatingScript script = (DelegatingScript)sh.parse(new File(dslFile))
    script.setDelegate(this.delegate);
    return script.run();
  }

  def nullIfEmpty(def value) {
    value == '' ? null : value
  }

  def buildFormalParametersFromFormXmlToProcedure(def proc, File formXml) {

    def formElements = new XmlSlurper().parseText(formXml.text)

    procedure proc.procedureName, {

      ec_parameterForm = formXml.text
      formElements.formElement.each { formElement ->
        def expansionDeferred = formElement.expansionDeferred == "true" ? "1" : "0"
        // println "expansionDeferred: ${formElement.property}: $expansionDeferred"

        formalParameter "$formElement.property",
            defaultValue: formElement.value,
            required: nullIfEmpty(formElement.condition) ? 0 : ( nullIfEmpty(formElement.required) ?: 0 ),
            description: formElement.documentation,
            type: formElement.type,
            label: formElement.label,
            expansionDeferred: expansionDeferred

        if (formElement['attachedAsParameterToStep'] && formElement['attachedAsParameterToStep'] != '') {
          formElement['attachedAsParameterToStep'].toString().split(',').each { attachToStep ->
            println("Attaching parameter $formElement.property to step $attachToStep")
            attachParameter(projectName: proc.projectName,
                procedureName: proc.procedureName,
                stepName: attachToStep,
                formalParameterName: formElement.property)
          }
        }

        //setup custom editor data for each parameter
        property 'ec_customEditorData', procedureName: proc.procedureName, {
          property 'parameters', {
            property "$formElement.property", {
              formType = 'standard'
              //println "Form element $formElement.property, type: '${formElement.type.toString()}'"
              if ('checkbox' == formElement.type.toString()) {
                checkedValue = formElement.checkedValue?:'true'
                uncheckedValue = formElement.uncheckedValue?:'false'
                initiallyChecked = formElement.initiallyChecked?:'0'
              } else if ('select' == formElement.type.toString() ||
                  'radio' == formElement.type.toString()) {
                int count = 0
                property "options", {
                  formElement.option.each { option ->
                    count++
                    property "option$count", {
                      property 'text', value: "${option.name}"
                      property 'value', value: "${option.value}"
                    }
                  }
                  type = 'list'
                  optionCount = count
                }
              }
            }
          }
        }
      }
    }
  }

  def buildFormalParametersFromFormXmlToPipeline(def pipe, File formXml) {

    def formElements = new XmlSlurper().parseText(formXml.text)

    pipeline pipe.pipelineName, {

      ec_parameterForm = formXml.text
      formElements.formElement.each { formElement ->
        def expansionDeferred = formElement.expansionDeferred == "true" ? "1" : "0"
        // println "expansionDeferred: ${formElement.property}: $expansionDeferred"

        formalParameter "$formElement.property",
            defaultValue: formElement.value,
            required: nullIfEmpty(formElement.condition) ? 0 : ( nullIfEmpty(formElement.required) ?: 0 ),
            description: formElement.documentation,
            type: formElement.type,
            label: formElement.label,
            expansionDeferred: expansionDeferred

        //setup custom editor data for each parameter
        property 'ec_customEditorData', pipelineName: pipe.pipelineName, {
          property 'parameters', {
            property "$formElement.property", {
              formType = 'standard'
              // println "Form element $formElement.property, type: '${formElement.type.toString()}'"
              if ('checkbox' == formElement.type.toString()) {
                checkedValue = formElement.checkedValue?:'true'
                uncheckedValue = formElement.uncheckedValue?:'false'
                initiallyChecked = formElement.initiallyChecked?:'0'
              } else if ('select' == formElement.type.toString() ||
                  'radio' == formElement.type.toString()) {
                int count = 0
                property "options", {
                  formElement.option.each { option ->
                    count++
                    property "option$count", {
                      property 'text', value: "${option.name}"
                      property 'value', value: "${option.value}"
                    }
                  }
                  type = 'list'
                  optionCount = count
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * NMB-27865: Intercept the DslDelegate
   * so it can be set as the delegate on the
   * dsl scripts being evaluated in context
   * of the parent dsl script.
   * Before setting the delegate, also capture
   * the script's class loader before the dslDelegate
   * highjacks the calls. This is needed to get the
   * reference to the groovy class loader that used for
   * evaluating the DSL script passed in to evalDsl.
   */
  private def delegate;
  private def scriptClassLoader;

  public void setDelegate(DslDelegate delegate) {
    this.scriptClassLoader = this.class.classLoader
    this.delegate = delegate;
    super.setDelegate(delegate)
  }

  public def getDelegate(){
    this.delegate
  }
}
