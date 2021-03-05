package rundeck.init

import grails.testing.mixin.integration.Integration
import groovy.sql.Sql
import org.grails.plugins.databasemigration.DatabaseMigrationTransactionManager
import org.grails.plugins.databasemigration.liquibase.GrailsLiquibase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import rundeckapp.init.RundeckDbMigration
import rundeckapp.init.RundeckInitConfig
import spock.lang.AutoCleanup
import spock.lang.Specification

import javax.sql.DataSource

@Integration
class RundeckDbMigrationTest extends Specification {

    @Autowired
    DataSource dataSource

    @Autowired
    ApplicationContext applicationContext

    def grailsApplication

    @AutoCleanup
    Sql sql


    def tableList = ['AUTH_TOKEN', 'BASE_REPORT', 'EXECUTION', 'JOB_FILE_RECORD', 'LOG_FILE_STORAGE_REQUEST',
                     'NOTIFICATION', 'NODE_FILTER', 'ORCHESTRATOR', 'PLUGIN_META', 'PROJECT', 'RDUSER', 'RDOPTION',
                     'RDOPTION_VALUES', 'REFERENCED_EXECUTION', 'REPORT_FILTER', 'SCHEDULED_EXECUTION',
                     'SCHEDULED_EXECUTION_FILTER', 'SCHEDULED_EXECUTION_STATS', 'STORAGE', 'STORED_EVENT', 'WEBHOOK',
                     'WORKFLOW', 'WORKFLOW_STEP', 'WORKFLOW_WORKFLOW_STEP']

    def setup() {
        sql = new Sql(dataSource)
    }

    def cleanupSpec() {
     }

    void "db migrations updates database to the current version"(){
        setup:
        sql.execute('DELETE FROM DATABASECHANGELOG')
        tableList.each{table->
            String statement =  "DROP TABLE ${table}"
            sql.execute(statement)
        }

        expect:
        sql.firstRow('SELECT COUNT(*) AS num FROM DATABASECHANGELOG').num == 0

        when:
        runMigrations()

        then:
        sql.firstRow('SELECT COUNT(*) AS num FROM DATABASECHANGELOG').num > 0
        def tables = sql.rows(' select * from information_schema.tables')
        tableList.each{table->
            tables*.TABLE_NAME.contains("${table}")
        }

    }

    void "Rolls back the database to the state it was in when the tag was applied"(){
        given:
        String tagName = "4.3.0"
        RundeckDbMigration rundeckDbMigration = new RundeckDbMigration(applicationContext, grailsApplication)

        expect:
        sql.firstRow('SELECT COUNT(*) AS num FROM DATABASECHANGELOG').num > 0

        when:
        rundeckDbMigration.rollback(tagName)

        then:
        sql.firstRow('SELECT COUNT(*) AS num FROM DATABASECHANGELOG').num == 0
        def tables = sql.rows(' select * from information_schema.tables')
        tableList.each{table->
            !(tables*.TABLE_NAME.contains("${table}"))
        }

        cleanup:
        runMigrations()
    }

    def runMigrations(){

        new DatabaseMigrationTransactionManager(applicationContext, 'dataSource').withTransaction {
            GrailsLiquibase gl = new GrailsLiquibase(applicationContext)
            gl.dataSource = applicationContext.getBean('dataSource', DataSource)
            gl.changeLog = grailsApplication.config.getProperty("grails.plugin.databasemigration.changelog", String)
            gl.dataSourceName = 'dataSource'
            gl.afterPropertiesSet()
        }
    }
}
