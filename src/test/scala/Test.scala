package app

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActors, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.concurrent.duration._

class TestAccountSharding extends TestKit(ActorSystem("minimal")) with WordSpecLike
  with ImplicitSender with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val accounts = Sharding.accounts(system)

  "a sharded account" must {

    "act normally" in {

      accounts ! Query("83")
      expectMsg(0)

      accounts ! Deposit("83", 50)
      expectMsg(Accepted)

      accounts ! Withdraw("83", 50)
      expectMsg(Accepted)

      accounts ! Deposit("83", 25)
      expectMsg(Accepted)

      accounts ! Query("83")
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
  implicit val timeout = akka.util.Timeout(2 seconds)

  "an account actor" must {

    "be able to respond to a query" in {

      val account = system.actorOf(Props(new AccountActor()), "42")

      account ! Query("42")
      expectMsg(0)

      system.stop(account)

    }

    "be able to respond to Deposit, Withdraw and Query messages" in {

      val account = system.actorOf(Props(new AccountActor()), "42")
      account ! Deposit("42", 50)
      account ! Deposit("42", 20)
      account ! Withdraw("42", 10)

      expectMsg(Accepted)
      expectMsg(Accepted)
      expectMsg(Accepted)

      account ! Query("42")
      expectMsg(60)

      system.stop(account)

    }

    "recover state via event sourcing" in {

      val accountA = system.actorOf(Props(new AccountActor()), "56")
      accountA ! Deposit("56", 50)
      accountA ! Deposit("56", 20)
      accountA ! Withdraw("56", 10)

      expectMsg(Accepted)
      expectMsg(Accepted)
      expectMsg(Accepted)

      system.stop(accountA)
      Thread.sleep(100) // wait for the actor to die

      val accountB = system.actorOf(Props(new AccountActor()), "56")
      accountB ! Query("56")
      expectMsg(60)
      system.stop(accountB)

    }

    "be able to vote no on a transaction request" in {

      val account = system.actorOf(Props(new AccountActor()), "53")
      account ! Deposit("53", 0)
      expectMsg(Accepted)
      account ! Vote("53", MoneyTransaction("tId", "53", "42", 50))
      expectMsg(No("53"))
      system.stop(account)

    }

    "be able to be queried while waiting for a commit message" in {

      val account = system.actorOf(Props(new AccountActor()), "83")
      account ! Deposit("83", 50)
      expectMsg(Accepted)

      account ! Query("83")
      expectMsg(50)

      account ! Vote("83", MoneyTransaction("tId", "83", "86", 50))
      expectMsg(Yes("83"))

      account ! Query("83")
      expectMsg(50)

      system.stop(account)

    }

    "be able to vote yes, receive an abort message, end in a proper state" in {

      val account = system.actorOf(Props(new AccountActor()), "86")
      account ! Deposit("86", 50)
      expectMsg(Accepted)

      account ! Vote("86", MoneyTransaction("tId", "86", "83", 50))
      expectMsg(Yes("86"))

      account ! Query("86")
      expectMsg(50)

      account ! IsLocked("86")
      expectMsg(true)

      account ! Abort("86", "tId")

      account ! Query("86")
      expectMsg(50)

      account ! IsLocked("86")
      expectMsg(false)

      system.stop(account)

    }

    "be able to rollback itself after voting yes but not receiving a commit message within a reasonable time" in {

      val account = system.actorOf(Props(new AccountActor()), "94")
      account ! Deposit("94", 50)
      expectMsg(Accepted)

      account ! Vote("94", MoneyTransaction("tId",  "94", "83", 50))
      expectMsg(Yes("94"))
      account ! IsLocked("94")
      expectMsg(true)

      Thread.sleep(500)

      account ! Query("94")
      expectMsg(50)
      account ! IsLocked("94")
      expectMsg(false)

      system.stop(account)

    }

    "be able to do engage in a transaction from start to finish" in {

      val account = system.actorOf(Props(new AccountActor()), "99")
      account ! Deposit("99", 50)
      expectMsg(Accepted)

      account ! Vote("99", MoneyTransaction("tId", "99", "98", 25))
      expectMsg(Yes("99"))
      account ! IsLocked("99")
      expectMsg(true)
      account ! Query("99")
      expectMsg(50)

      account ! Commit("99", "tId")
      expectMsg(AckCommit("99", "tId"))

      account ! Query("99")
      expectMsg(50) // still previous balance
      account ! IsLocked("99")
      expectMsg(true)

      account ! Finalize("99", MoneyTransaction("tId", "99", "98", 25), "deliveryId")
      expectMsg(AckFinalize("99", "tId"))

      // sending twice is ok
      account ! Finalize("99", MoneyTransaction("tId", "99", "98", 25), "deliveryId")
      expectMsg(AckFinalize("99", "tId"))

      account ! Query("99")
      expectMsg(25)

      system.stop(account)

    }


    "be able to complete a transaction from start to finish, even if it has to restart along the way" in {

      val accountA = system.actorOf(Props(new AccountActor()), "100")
      accountA ! Deposit("100", 50)
      expectMsg(Accepted)

      accountA ! Vote("100", MoneyTransaction("tId", "100", "0", 25))
      expectMsg(Yes("100"))
      accountA ! IsLocked("100")
      expectMsg(true)
      accountA ! Query("100")
      expectMsg(50)

      accountA ! Commit("100", "tId")
      expectMsg(AckCommit("100", "tId"))

      system.stop(accountA)
      Thread.sleep(100) // wait for actor to die

      val accountB = system.actorOf(Props(new AccountActor()), "100") // restart

      accountB ! Query("100")
      expectMsg(50) // still previous balance
      accountB ! IsLocked("100")
      expectMsg(true)

      accountB ! Finalize("99", MoneyTransaction("tId", "100", "0", 25))
      expectMsg(AckFinalize("100", "tId"))

      // sending twice is ok
      accountB ! Finalize("100", MoneyTransaction("tId", "100", "0", 25))
      expectMsg(AckFinalize("100", "tId"))

      accountB ! Query("100")
      expectMsg(25)

      system.stop(accountB)

    }

    "be able to handle a rollback" in {

      val account = system.actorOf(Props(new AccountActor()), "X")
      account ! Deposit("X", 10)
      expectMsg(Accepted)

      account ! Query("X")
      expectMsg(10)

      account ! Vote("X", MoneyTransaction("tId", "X", "Y", 5))
      expectMsg(Yes("X"))

      account ! Commit("X", "tId")
      expectMsg(AckCommit("X", "tId"))

      account ! Query("X")
      expectMsg(10)

      account ! Rollback("X", MoneyTransaction("tId", "X", "Y", 5), "deliveryId")
      expectMsg(AckRollback("X", "tId"))

      account ! Query("X")
      expectMsg(10)


    }

  }


}
