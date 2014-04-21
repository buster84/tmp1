package controllers

import play.api._
import play.api.mvc._
import play.api.libs._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.Akka
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import akka.actor.Actor
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util.Try
import scala.util.Success
import scala.util.Failure

object Application extends Controller {

  val random = new java.util.Random()

  def indexasync = Action.async {
    val email = random.nextInt(50000).toString + "@example.com"
    (for{
      user   <- UsersA.insert(email)
      number <- EventApplicationA.insert(user.id)
    } yield {
      Ok(number.toString)
    }) recover {
      case e: Exception  => 
        Ok(e.getMessage())
    }
  }

  def indexsync = Action {
    val email = random.nextInt(50000).toString + "@example.com"
    val user  = Users.insert(email)
    EventApplication.insert(user.id).fold(
      error  => Ok(error),
      number => Ok(number.toString)
    )
  }

  def debug = Action {
    Ok(s"Users.lastId: ${Users.lastId.toString}, Number of users applied: ${EventApplication.userIds.length}")
  }
}

case class User(id: Long, email: String)
object Users {
  var lastId = 1L
  var userCollection :Map[String, User] = Map.empty[String, User]

  def insert(email: String): User = synchronized {
    userCollection.get(email).getOrElse{
      val id   = getAndIncrement()
      val user = User(id, email)
      userCollection = userCollection ++ Map(email -> user)
      user
    }
  }

  def getAndIncrement() = synchronized {
    val id = lastId
    lastId = lastId + 1
    id
  }
}

object EventApplication {
  val limit  = 10000
  var userIds: Seq[Long] = Seq.empty[Long]

  def checkApplied(userId: Long): Boolean = {
    userIds.contains(userId)
  }

  def insert(userId: Long): Either[String, Long] = synchronized {
    if(!checkApplied(userId)){
      if(userIds.length < limit){
        userIds = userIds ++ Seq(userId)
        Right(userIds.length)
      } else {
        Left("Limit over")
      }
    } else {
      Left("Already applied")
    }
  }
}


object UsersA {
  case class Insert(email: String)
  class UsersActor extends Actor {
    var lastId = 1L
    var userCollection :Map[String, User] = Map.empty[String, User]

    def receive = {
      case Insert(email) =>
        val user = userCollection.get(email).getOrElse{
          val id   = lastId
          lastId = lastId + 1
          val user = User(id, email)
          userCollection = userCollection ++ Map(email -> user)
          user
        }
        sender ! user
    }
  }

  implicit val timeout = Timeout(Duration(5, SECONDS))
  val actor = Akka.system.actorOf(Props[UsersActor], name = "usersactor")
  def insert(email: String): Future[User] = (actor ? Insert(email)).mapTo[User]
}

object EventApplicationA {
  case class Apply(id: Long)
  class EventApplicationActor extends Actor {
    val limit  = 10000
    var userIds: Seq[Long] = Seq.empty[Long]

    def receive = {
      case Apply(id) =>
        if(!userIds.contains(id)){
          if(userIds.length < limit){
            userIds = userIds ++ Seq(id)
            sender ! Success(userIds.length)
          } else {
            sender ! Failure(new Exception("Limit over"))
          }
        } else {
          sender ! Failure(new Exception("Already applied"))
        }
    }
  }

  implicit val timeout = Timeout(Duration(5, SECONDS))
  val actor = Akka.system.actorOf(Props[EventApplicationActor], name = "eventActor")
  def insert(id: Long): Future[Int] = (actor ? Apply(id)).flatMap{
    case Success(number: Int) => Future.successful(number)
    case Failure(e)           => Future.failed(e)
  }
}

object Contexts {
  implicit val myExecutionContext: ExecutionContext = Akka.system.dispatchers.lookup("my-context")
}
