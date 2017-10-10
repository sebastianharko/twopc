package app

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActors, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, FunSuite, WordSpecLike}

import scala.collection.mutable
import scala.concurrent.duration._

class TestAccountSharding extends TestKit(ActorSystem("minimal")) with WordSpecLike
  with ImplicitSender with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val accounts: ActorRef = Sharding.accounts(system)

  "a sharded account" must {

    "act normally" in {

      accounts ! GetBalance("83")
      expectMsg(0)

      accounts ! ChangeBalance("83", 50)
      expectMsg(Accepted(None))

      accounts ! ChangeBalance("83", -50)
      expectMsg(Accepted(None))

      accounts ! ChangeBalance("83", 25)
      expectMsg(Accepted(None))

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
      account ! ChangeBalance("42", 50)
      expectMsg(Accepted())
      account ! ChangeBalance("42", 20)
      expectMsg(Accepted())
      account ! ChangeBalance("42", -10)
      expectMsg(Accepted())

      account ! GetBalance("42")
      expectMsg(60)

      system.stop(account)

    }

    "recover state via event sourcing" in {

      val accountA = system.actorOf(Props(new AccountActor()), "56")
      accountA ! ChangeBalance("56", 50)
      expectMsg(Accepted())
      accountA ! ChangeBalance("56", 20)
      expectMsg(Accepted())
      accountA ! ChangeBalance("56", -10)
      expectMsg(Accepted())

      system.stop(accountA)
      Thread.sleep(100) // wait for the actor to die

      val accountB = system.actorOf(Props(new AccountActor()), "56")
      accountB ! GetBalance("56")
      expectMsg(60)
      system.stop(accountB)

    }

    "be able to vote no on a transaction request" in {

      val account = system.actorOf(Props(new AccountActor()), "53")
      account ! ChangeBalance("53", 0)
      expectMsg(Accepted())
      account ! Vote("53", MoneyTransaction("tId", "53", "42", 50))
      expectMsg(No("53"))
      system.stop(account)

    }

    "be able to be queried while waiting for a commit message" in {

      val account = system.actorOf(Props(new AccountActor()), "83")
      account ! ChangeBalance("83", 50)
      expectMsg(Accepted())

      account ! GetBalance("83")
      expectMsg(50)

      account ! Vote("83", MoneyTransaction("tId", "83", "86", 50))
      expectMsg(Yes("83"))

      account ! GetBalance("83")
      expectMsg(50)

      system.stop(account)

    }

    "be able to vote yes, receive an abort message, end in a proper state" in {

      val account = system.actorOf(Props(new AccountActor()), "86")
      account ! ChangeBalance("86", 50)
      expectMsg(Accepted())

      account ! Vote("86", MoneyTransaction("tId", "86", "83", 50))
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
      account ! ChangeBalance("94", 50)
      expectMsg(Accepted())

      account ! Vote("94", MoneyTransaction("tId",  "94", "83", 50))
      expectMsg(Yes("94"))
      account ! IsLocked("94")
      expectMsg(true)

      Thread.sleep((1.25 * Coordinator.MaxTimeOutForCommitPhase).toInt)

      account ! GetBalance("94")
      expectMsg(50)
      account ! IsLocked("94")
      expectMsg(false)

      system.stop(account)

    }

    "be able to do engage in a transaction from start to finish" in {

      val account = system.actorOf(Props(new AccountActor()), "99")
      account ! ChangeBalance("99", 50)
      expectMsg(Accepted())

      account ! Vote("99", MoneyTransaction("tId", "99", "98", 25))
      expectMsg(Yes("99"))
      account ! IsLocked("99")
      expectMsg(true)
      account ! GetBalance("99")
      expectMsg(50)

      account ! Commit("99", "tId")
      expectMsg(AckCommit("99", "tId"))

      account ! GetBalance("99")
      expectMsg(50) // still previous balance
      account ! IsLocked("99")
      expectMsg(true)

      account ! Finalize("99", MoneyTransaction("tId", "99", "98", 25), 666)
      expectMsg(AckFinalize("99", "tId", 666))

      // sending twice is ok
      account ! Finalize("99", MoneyTransaction("tId", "99", "98", 25), 666)
      expectMsg(AckFinalize("99", "tId", 666))

      account ! GetBalance("99")
      expectMsg(25)

      system.stop(account)

    }


    "be able to complete a transaction from start to finish, even if it has to restart along the way" in {

      val accountA = system.actorOf(Props(new AccountActor()), "100")
      accountA ! ChangeBalance("100", 50)
      expectMsg(Accepted())

      accountA ! Vote("100", MoneyTransaction("tId", "100", "0", 25))
      expectMsg(Yes("100"))
      accountA ! IsLocked("100")
      expectMsg(true)
      accountA ! GetBalance("100")
      expectMsg(50)

      accountA ! Commit("100", "tId")
      expectMsg(AckCommit("100", "tId"))

      system.stop(accountA)
      Thread.sleep(100) // wait for actor to die

      val accountB = system.actorOf(Props(new AccountActor()), "100") // restart

      accountB ! GetBalance("100")
      expectMsg(50) // still previous balance
      accountB ! IsLocked("100")
      expectMsg(true)

      accountB ! Finalize("100", MoneyTransaction("tId", "100", "0", 25), 666)
      expectMsg(AckFinalize("100", "tId", 666))

      // sending twice is ok
      accountB ! Finalize("100", MoneyTransaction("tId", "100", "0", 25), 666)
      expectMsg(AckFinalize("100", "tId", 666))

      accountB ! GetBalance("100")
      expectMsg(25)

      system.stop(accountB)

    }

    "be able to handle a rollback" in {

      val account = system.actorOf(Props(new AccountActor()), "X")
      account ! ChangeBalance("X", 10)
      expectMsg(Accepted())

      account ! GetBalance("X")
      expectMsg(10)

      account ! Vote("X", MoneyTransaction("tId", "X", "Y", 5))
      expectMsg(Yes("X"))

      account ! Commit("X", "tId")
      expectMsg(AckCommit("X", "tId"))

      account ! GetBalance("X")
      expectMsg(10)

      account ! Rollback("X", MoneyTransaction("tId", "X", "Y", 5), 666)
      expectMsg(AckRollback("X", "tId", 666))

      account ! GetBalance("X")
      expectMsg(10)


    }

  }

}

class TestTimeOutManager extends TestKit(ActorSystem("minimal")) with WordSpecLike
    with ImplicitSender with  BeforeAndAfterAll {

    override def afterAll {
      TestKit.shutdownActorSystem(system)
    }

    val suggestedTimeOut = new AtomicInteger(1000)

    "a timeout manager" must {

      "work correctly" in {

        val t = system.actorOf(Props(new TimeOutManager(0, alpha = 0.25, suggestedTimeOut)))

        var s = List[Int]()

        var prev: Int = 0

        (1 to 10).foreach { i =>
          t ! StartTimer(i.toString, 0)
          t ! StopTimer(i.toString, i * 100)
          Thread.sleep(25)
          assert(suggestedTimeOut.get() > prev || prev == 0)
          prev = suggestedTimeOut.get()

        }

      }

    }
}

class TestCoordinator extends TestKit(ActorSystem("minimal")) with WordSpecLike
    with ImplicitSender with  BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val blackHole: ActorRef = system.actorOf(TestActors.blackholeProps)
  implicit val timeout = Timeout(2 seconds)

  val accounts: ActorRef = Sharding.accounts(system)

  "a coordinator" must {

    "be able to reject the transaction if no response from vote requests" in {

      val id = java.util.UUID.randomUUID().toString
      val coordinator = system.actorOf(Props(new Coordinator(blackHole, votingTimer = blackHole, commitTimer = blackHole)), id)
      watch(coordinator)
      coordinator ! MoneyTransaction(id, "A", "B", 25)
      expectMsg(1.25 * Coordinator.MaxTimeOutForVotingPhase milliseconds, Rejected(Some(id)))
      expectTerminated(coordinator)

    }

    "be able to reject the transaction if one the accounts votes no" in {

      accounts ! ChangeBalance("A", +100)
      expectMsg(Accepted())
      accounts ! ChangeBalance("B", +50)
      expectMsg(Accepted())

      val id = java.util.UUID.randomUUID().toString
      val coordinator = system.actorOf(Props(new Coordinator(accounts, votingTimer = blackHole, commitTimer = blackHole)), id)
      watch(coordinator)
      coordinator ! MoneyTransaction(id, "A", "B", 500)
      expectMsg(Rejected(Some(id)))
      expectTerminated(coordinator)

    }

    "be able to send commit messages if both the accounts vote yes" in {

      accounts ! ChangeBalance("X", +100)
      expectMsg(Accepted())
      accounts ! ChangeBalance("Y", +50)
      expectMsg(Accepted())

      val id = java.util.UUID.randomUUID().toString
      val coordinator = system.actorOf(Props(new Coordinator(accounts, votingTimer = blackHole, commitTimer = blackHole)), id)
      watch(coordinator)
      coordinator ! MoneyTransaction(id, "X", "Y", 50)
      expectMsg(Accepted(Some(id)))
      expectTerminated(coordinator)

      accounts ! GetBalance("X")
      expectMsg(50)

      accounts ! GetBalance("Y")
      expectMsg(100)

    }




  }


}