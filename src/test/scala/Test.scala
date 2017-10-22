package app

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActors, TestKit}
import akka.util.Timeout
import com.lightbend.cinnamon.akka.CinnamonMetrics
import com.lightbend.cinnamon.metric.{Counter, Rate}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import app.messages._

import scala.concurrent.duration._
import scala.language.postfixOps

class TestAccountSharding extends TestKit(ActorSystem("minimal")) with WordSpecLike
  with ImplicitSender with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val fakeRate: Rate = CinnamonMetrics(system).createRate("fakeRate")
  val fakeCounter: Counter = CinnamonMetrics(system).createCounter("fakeCounter")

  val accounts: ActorRef = AccountActor.accountsShardRegion(system)

  "a sharded account" must {

    "act normally" in {

      accounts ! GetBalance("83")
      expectMsg(0)

      accounts ! ChangeBalance("83", 50, true)
      expectMsg(Accepted("na"))

      accounts ! ChangeBalance("83", -50, true)
      expectMsg(Accepted("na"))

      accounts ! ChangeBalance("83", 25, true)
      expectMsg(Accepted("na"))

      accounts ! GetBalance("83")
      expectMsg(25)

      accounts ! IsLocked("83")
      expectMsg(false)

    }

  }

}

class TestSimpleAccountOps extends TestKit(ActorSystem("minimal")) with WordSpecLike
  with ImplicitSender with  BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val fakeRate: Rate = CinnamonMetrics(system).createRate("fake")
  val fakeCounter: Counter = CinnamonMetrics(system).createCounter("fakeCounter")

  val blackHole: ActorRef = system.actorOf(TestActors.blackholeProps)
  implicit val timeout = Timeout(2 seconds)

  "an account actor" must {

    "be able to respond to a query" in {

      val account = system.actorOf(Props(new AccountActor()), "42")

      account ! GetBalance("42")
      expectMsg(0)

      system.stop(account)

    }

    "be able to respond to Deposit, Withdraw and Query messages" in {

      val account = system.actorOf(Props(new AccountActor()), "42")
      account ! ChangeBalance("42", 50, true)
      expectMsg(Accepted("na"))
      account ! ChangeBalance("42", 20, true)
      expectMsg(Accepted("na"))
      account ! ChangeBalance("42", -10, true)
      expectMsg(Accepted("na"))

      account ! GetBalance("42")
      expectMsg(60)

      system.stop(account)

    }

    "recover state via event sourcing" in {

      val accountA = system.actorOf(Props(new AccountActor()), "56")
      accountA ! ChangeBalance("56", 50, true)
      expectMsg(Accepted("na"))
      accountA ! ChangeBalance("56", 20, true)
      expectMsg(Accepted("na"))
      accountA ! ChangeBalance("56", -10, true)
      expectMsg(Accepted("na"))

      system.stop(accountA)
      Thread.sleep(100) // wait for the actor to die

      val accountB = system.actorOf(Props(new AccountActor()), "56")
      accountB ! GetBalance("56")
      expectMsg(60)
      system.stop(accountB)

    }

    "be able to vote no on a transaction request" in {

      val account = system.actorOf(Props(new AccountActor()), "53")
      account ! ChangeBalance("53", 0, true)
      expectMsg(Accepted("na"))
      account ! Vote("53", "tId", "53", "42", 50)
      expectMsg(No("53"))
      system.stop(account)

    }

    "be able to be queried while waiting for a commit message" in {

      val account = system.actorOf(Props(new AccountActor()), "83")
      account ! ChangeBalance("83", 50, true)
      expectMsg(Accepted("na"))

      account ! GetBalance("83")
      expectMsg(50)

      account ! Vote("83", "tId", "83", "86", 50)
      expectMsg(Yes("83"))

      account ! GetBalance("83")
      expectMsg(50)

      system.stop(account)

    }

    "be able to vote yes, receive an abort message, end in a proper state" in {

      val account = system.actorOf(Props(new AccountActor()), "86")
      account ! ChangeBalance("86", 50, true)
      expectMsg(Accepted("na"))

      account ! Vote("86", "tId", "86", "83", 50)
      expectMsg(Yes("86"))

      account ! GetBalance("86")
      expectMsg(50)

      account ! IsLocked("86")
      expectMsg(true)

      account ! Abort("86", "tId")

      account ! GetBalance("86")
      expectMsg(50)

      account ! IsLocked("86")
      expectMsg(false)

      system.stop(account)

    }

    "be able to rollback itself after voting yes but not receiving a commit message within a reasonable time" in {

      val account = system.actorOf(Props(new AccountActor()), "94")
      account ! ChangeBalance("94", 50, true)
      expectMsg(Accepted("na"))

      account ! Vote("94", "tId",  "94", "83", 50)
      expectMsg(Yes("94"))
      account ! IsLocked("94")
      expectMsg(true)

      Thread.sleep(1000)

      account ! GetBalance("94")
      expectMsg(50)
      account ! IsLocked("94")
      expectMsg(false)

      system.stop(account)

    }

    "be able to engage in a transaction from start to finish" in {

      val account = system.actorOf(Props(new AccountActor()), "99")
      account ! ChangeBalance("99", 50, true)
      expectMsg(Accepted("na"))

      account ! Vote("99", "tId", "99", "98", 25)
      expectMsg(Yes("99"))
      account ! IsLocked("99")
      expectMsg(true)
      account ! GetBalance("99")
      expectMsg(50)

      account ! Commit("99", "tId")
      expectMsg(AckCommit("99"))

      account ! GetBalance("99")
      expectMsg(50) // still previous balance
      account ! IsLocked("99")
      expectMsg(true)

      account ! Finalize("99", "tId", 666)
      expectMsg(AckFinalize("99", 666))

      // sending twice is ok
      account ! Finalize("99", "tId", 666)
      expectMsg(AckFinalize("99", 666))

      Thread.sleep(200)

      account ! GetBalance("99")
      expectMsg(25)

      system.stop(account)

    }


    "be able to complete a transaction from start to finish, even if it has to restart along the way" in {

      val accountA = system.actorOf(Props(new AccountActor()), "100")
      accountA ! ChangeBalance("100", 50, true)
      expectMsg(Accepted("na"))

      accountA ! Vote("100", "tId", "100", "0", 25)
      expectMsg(Yes("100"))
      accountA ! IsLocked("100")
      expectMsg(true)
      accountA ! GetBalance("100")
      expectMsg(50)

      accountA ! Commit("100", "tId")
      expectMsg(AckCommit("100"))

      system.stop(accountA)
      Thread.sleep(100) // wait for actor to die

      val accountB = system.actorOf(Props(new AccountActor()), "100") // restart

      accountB ! GetBalance("100")
      expectMsg(50) // still previous balance
      accountB ! IsLocked("100")
      expectMsg(true)

      accountB ! Finalize("100", "tId", 666)
      expectMsg(AckFinalize("100", 666))

      // sending twice is ok
      accountB ! Finalize("100", "tId", 666)
      expectMsg(AckFinalize("100", 666))

      accountB ! GetBalance("100")
      expectMsg(25)

      system.stop(accountB)

    }

    "be able to handle a rollback" in {

      val account = system.actorOf(Props(new AccountActor()), "X")
      account ! ChangeBalance("X", 10, true)
      expectMsg(Accepted("na"))

      account ! GetBalance("X")
      expectMsg(10)

      account ! Vote("X", "tId", "X", "Y")
      expectMsg(Yes("X"))

      account ! Commit("X", "tId")
      expectMsg(AckCommit("X"))

      account ! GetBalance("X")
      expectMsg(10)

      account ! Rollback("X", "tId", 666)
      expectMsg(AckRollback("X", 666))

      account ! GetBalance("X")
      expectMsg(10)


    }

  }

}

class TestCoordinator extends TestKit(ActorSystem("minimal")) with WordSpecLike
    with ImplicitSender with  BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val fakeRate = CinnamonMetrics(system).createRate("fake")
  val fakeCounter: Counter = CinnamonMetrics(system).createCounter("fakeCounter")

  val blackHole: ActorRef = system.actorOf(TestActors.blackholeProps)
  implicit val timeout = Timeout(2 seconds)

  val accounts: ActorRef = AccountActor.accountsShardRegion(system)

  "a coordinator" must {

    "be able to reject the transaction if no response from vote requests" in {

      val id = java.util.UUID.randomUUID().toString
      val coordinator = system.actorOf(Props(new Coordinator(blackHole)), id)
      watch(coordinator)
      coordinator ! MoneyTransaction(id, "A", "B", 25, true)
      expectMsg((1.25 * Coordinator.TimeOutForVotingPhase)._1 milliseconds, Rejected(id))
      expectTerminated(coordinator)

    }

    "be able to reject the transaction if one the accounts votes no" in {

      accounts ! ChangeBalance("A", +100, true)
      expectMsg(Accepted("na"))
      accounts ! ChangeBalance("B", +50, true)
      expectMsg(Accepted("na"))

      val id = java.util.UUID.randomUUID().toString
      val coordinator = system.actorOf(Props(new Coordinator(accounts)), id)
      watch(coordinator)
      coordinator ! MoneyTransaction(id, "A", "B", 500, true)
      expectMsg(Rejected(id))
      expectTerminated(coordinator)

    }

    "be able to send commit messages if both the accounts vote yes" in {

      accounts ! ChangeBalance("X", +100, true)
      expectMsg(Accepted("na"))
      accounts ! ChangeBalance("Y", +50, true)
      expectMsg(Accepted("na"))

      Thread.sleep(100)

      accounts ! GetBalance("X")
      expectMsg(3 seconds, "initially X should be 100", 100)

      accounts ! GetBalance("Y")
      expectMsg(3 seconds, "initially Y should be 50", 50)


      val id = java.util.UUID.randomUUID().toString
      val coordinator = system.actorOf(Props(new Coordinator(accounts)), id)
      watch(coordinator)
      coordinator ! MoneyTransaction(id, "X", "Y", 50, true)
      expectMsgClass(classOf[Accepted])
      expectTerminated(coordinator)

      accounts ! GetBalance("X")
      expectMsg(50)

      accounts ! GetBalance("Y")
      expectMsg(100)

    }

  }


}