package org.thp.thehive.controllers.v0

import akka.stream.Materializer
import org.specs2.mock.Mockito
import org.specs2.specification.core.{Fragment, Fragments}
import org.thp.scalligraph.AppBuilder
import org.thp.scalligraph.auth.UserSrv
import org.thp.scalligraph.controllers.{AuthenticateSrv, TestAuthenticateSrv}
import org.thp.scalligraph.models.{Database, DatabaseProviders, DummyUserSrv, Schema}
import org.thp.scalligraph.services.{LocalFileSystemStorageSrv, StorageSrv}
import org.thp.thehive.dto.v0.{OutputLog, OutputTask}
import org.thp.thehive.models._
import org.thp.thehive.services.LocalUserSrv
import play.api.libs.json.Json
import play.api.test.{FakeRequest, PlaySpecification}
import play.api.{Configuration, Environment}

class LogCtrlTest extends PlaySpecification with Mockito {
  val dummyUserSrv          = DummyUserSrv(permissions = Permissions.all)
  val config: Configuration = Configuration.load(Environment.simple())

  Fragments.foreach(new DatabaseProviders(config).list) { dbProvider ⇒
    val app: AppBuilder = AppBuilder()
      .bind[UserSrv, LocalUserSrv]
      .bindToProvider(dbProvider)
      .bind[AuthenticateSrv, TestAuthenticateSrv]
      .bind[StorageSrv, LocalFileSystemStorageSrv]
      .bind[Schema, TheHiveSchema]
      .addConfiguration("play.modules.disabled = [org.thp.scalligraph.ScalligraphModule, org.thp.thehive.TheHiveModule]")
    step(setupDatabase(app)) ^ specs(dbProvider.name, app) ^ step(teardownDatabase(app))
  }

  def setupDatabase(app: AppBuilder): Unit =
    app.instanceOf[DatabaseBuilder].build()(app.instanceOf[Database], dummyUserSrv.initialAuthContext)

  def teardownDatabase(app: AppBuilder): Unit = app.instanceOf[Database].drop()

  def specs(name: String, app: AppBuilder): Fragment = {
    val logCtrl: LogCtrl                = app.instanceOf[LogCtrl]
    implicit lazy val mat: Materializer = app.instanceOf[Materializer]

    s"[$name] log controller" should {

      "be able to create, retrieve and patch a log" in {
        val tList = tasksList(app)
        val task  = tList.find(_.title == "case 1 task").get
        val request = FakeRequest("POST", s"/api/case/task/${task.id}/log")
          .withHeaders("user" → "user1")
          .withJsonBody(Json.parse("""
              {"message":"log 1\n\n### yeahyeahyeahs", "deleted":false}
            """.stripMargin))
        val result = logCtrl.create(task.id)(request)

        status(result) shouldEqual 201

        val requestSearch = FakeRequest("POST", s"/api/case/task/log/_search")
          .withHeaders("user" → "user1")
          .withJsonBody(Json.parse(s"""
              {
                "query":{
                   "_and":[
                      {
                         "_and":[
                            {
                               "_parent":{
                                  "_type":"case_task",
                                  "_query":{
                                     "_id":"${task.id}"
                                  }
                               }
                            },
                            {
                               "_not":{
                                  "status":"Deleted"
                               }
                            }
                         ]
                      }
                   ]
                }
             }
            """.stripMargin))
        val resultSearch = logCtrl.search(requestSearch)

        status(resultSearch) shouldEqual 200

        val logJson = contentAsJson(resultSearch)
        val log     = logJson.as[Seq[OutputLog]].head
        val expected = OutputLog(
          _id = log._id,
          id = log.id,
          createdBy = "user1",
          createdAt = log.createdAt,
          _type = "case_task_log",
          message = "log 1\n\n### yeahyeahyeahs",
          startDate = log.createdAt,
          status = "Ok",
          owner = "user1"
        )

        logJson.toString shouldEqual Json.toJson(Seq(expected)).toString

        val requestPatch = FakeRequest("PATCH", s"/api/case/task/log/${log.id}")
          .withHeaders("user" → "user1")
          .withJsonBody(Json.parse(s"""
              {
                "message":"yeah",
                "deleted": true
             }
            """.stripMargin))
        val resultPatch = logCtrl.update(log.id)(requestPatch)

        status(resultPatch) shouldEqual 204
      }
    }
  }

  def tasksList(app: AppBuilder): Seq[OutputTask] = {
    val taskCtrl    = app.instanceOf[TaskCtrl]
    val requestList = FakeRequest("GET", "/api/case/task").withHeaders("user" → "user1")
    val resultList  = taskCtrl.list(requestList)

    status(resultList) shouldEqual 200

    contentAsJson(resultList).as[Seq[OutputTask]]
  }
}
