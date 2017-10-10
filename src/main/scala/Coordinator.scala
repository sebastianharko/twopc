package app

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorLogging, ActorRef, Timers}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import akka.testkit.TestActors
import scala.concurrent.duration._
import scala.collection.mutable
import scala.language.postfixOps

/*

    +----------------------+                        +------------------------+    timed out waiting for responses
    |                      |     all votes 'Yes'    | Send a Commit  message ------\
    |   Ask participants   |             ------------ to every  participant  |      -------------\
    |   to vote            |------------/           | (at most once delivery)|                    ------+------------------+
    |                      |                        +-----|------------|-----+                          |  Send  Rollback  |
    +----------------------+                              |            |                                |  message  (at    |
                                                          |            |                                |  least once      |
    +----------------------+     acknowledge              |            |all participants                |  delivery)       |
    |   Write  transaction |------------------------------+            |acknowledge the Commit          +------------------+
    |   to coordinator's   |                                           |message
    |   event log          |                                           |
    +----------------------+                                     +-----|------------------+
                                                                 |  Send Finalize message |
                                                                 |  (at least once        |
                                                                 |    delivery)           |
                                                                 |                        |
                                                                 +------------------------+

*/

case class StartTimer(id: String, at: Long)

case class StopTimer(id: String, at: Long)

class TimeOutManager(maximumTimeOutMillis: Int, alpha: Double, ref: AtomicInteger) extends Actor with Timers with ActorLogging {

  private val exponentialMovingAverage = new ExponentialMovingAverage(alpha)

  val startTimes: mutable.Map[String, Long] = mutable.Map[String, Long]()

  override def receive: Receive = {
    case StartTimer(id: String, at: Long) =>
      startTimes += id -> at
    case StopTimer(id: String, at: Long) if startTimes.contains(id) =>
      var elapsed = at - startTimes(id)
      startTimes.remove(id)
      exponentialMovingAverage.add(elapsed)
      ref.set(math.min(exponentialMovingAverage.getCurrentValue.toInt, maximumTimeOutMillis))
      log.info(s"suggested timeout is ${ref.get()}")
  }

}


case class MoneyTransaction(transactionId: String,
    sourceAccountId: String,
    destinationAccountId: String,
    amount: Int) {

  require(sourceAccountId != destinationAccountId)
  require( amount >= 0)

}

case class Vote(accountId: String,
    moneyTransaction: MoneyTransaction)

case class Yes(fromAccountId: String)

case class No(fromAccountId: String)

case class Commit(accountId: String,
    transactionId: String)

case class AckCommit(accountId: String,
    transactionId: String)

case class Rollback(accountId: String,
    transaction: MoneyTransaction,
    deliveryId: Long)

case class AckRollback(accountId: String,
    tId: String,
    deliveryId: Long)

case class Finalize(accountId: String,
    transaction: MoneyTransaction,
    deliveryId: Long)

case class AckFinalize(accountId: String,
    tId: String,
    deliveryId: Long)

case class Accepted(transactionId: Option[String] = None)

case class Rejected(transactionId: Option[String] = None)

case class TimedOut(transactionId: String)

case class Abort(accountId: String, transactionId: String)

case class Finalizing(transactionId: String,
    sourceAccountId: String,
    destinationAccountId: String)

case class Rollingback(transactionId: String,
    sourceAccountId: String,
    destinationAccountId: String)

case object Check

case object StartFinalize

case object StartRollback

case object StartVotingProcess


object Coordinator {

  type AccountId = String

  val MaxTimeOutForVotingPhase = 4000

  val MaxTimeOutForCommitPhase = 4000

  val TimeOutForVotingPhase = new AtomicInteger(MaxTimeOutForVotingPhase)

  val TimeOutForCommitPhase = new AtomicInteger(MaxTimeOutForCommitPhase)

}

class Coordinator(shardedAccounts: ActorRef,
    timeOutForVotingPhase: AtomicInteger = Coordinator.TimeOutForVotingPhase,
    timeOutForCommitPhase: AtomicInteger = Coordinator.TimeOutForCommitPhase,
    votingTimer: ActorRef,
    commitTimer: ActorRef) extends PersistentActor with ActorLogging with Timers with AtLeastOnceDelivery {

  import Coordinator._

  var phase: Any = _

  var moneyTransaction: MoneyTransaction = _
  var replyTo: ActorRef = context.actorOf(TestActors.blackholeProps)

  def getTimeOutForVothingPhase = {
    val r = timeOutForVotingPhase.get()
    if (r == 0)
      Coordinator.MaxTimeOutForVotingPhase milliseconds
    else
      r milliseconds
  }

  def getTimeOutForCommitPhase = {
    val r = timeOutForCommitPhase.get()
    if (r == 0)
      Coordinator.MaxTimeOutForCommitPhase milliseconds
    else
      r milliseconds
  }


  override def receiveCommand: Receive = {

    case transaction: MoneyTransaction =>
      assert(transaction.transactionId == self.path.name)
      persistAsync(transaction){ _ => phase = 'Initiated; self ! Check }
      onEvent(transaction)
      this.replyTo = sender()
      self ! StartVotingProcess

    case StartVotingProcess =>
      votingTimer ! StartTimer(self.path.name, System.currentTimeMillis())
      shardedAccounts ! Vote(moneyTransaction.sourceAccountId, moneyTransaction)
      shardedAccounts ! Vote(moneyTransaction.destinationAccountId, moneyTransaction)
      timers.startSingleTimer('WaitingForVotes, TimedOut(moneyTransaction.transactionId), getTimeOutForVothingPhase)
      context.become(waitingForVoteResults, discardOld = true)
      log.info("started voting process")
    }

  val yesVotes: mutable.Set[AccountId] = mutable.Set[AccountId]()

  def waitingForVoteResults: Receive = {

    case TimedOut(transactionId) =>
      assert(transactionId == moneyTransaction.transactionId)
      votingTimer ! StopTimer(self.path.name, System.currentTimeMillis())
      replyTo ! Rejected(Some(moneyTransaction.transactionId))
      shardedAccounts ! Abort(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      timers.cancelAll()
      log.info("timed out (voting process)")
      context.stop(self)

    case AccountStashOverflow(someAccountId) =>
      assert(someAccountId == moneyTransaction.sourceAccountId || someAccountId == moneyTransaction.destinationAccountId)
      self ! No(someAccountId)

    case No(someAccountId) =>
      assert(someAccountId == moneyTransaction.sourceAccountId || someAccountId == moneyTransaction.destinationAccountId)
      replyTo ! Rejected(Some(moneyTransaction.transactionId))
      shardedAccounts ! Abort(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      timers.cancelAll()
      log.info("one of the participants voted no")
      context.stop(self)

    case Yes(accId) =>
      assert(accId == moneyTransaction.sourceAccountId || accId == moneyTransaction.destinationAccountId)
      log.info("received yes vote")
      yesVotes +=  accId
      self ! Check

    case Check if yesVotes.size == 2 && phase == 'Initiated =>
      votingTimer ! StopTimer(self.path.name, System.currentTimeMillis())
      commitTimer ! StartTimer(self.path.name, System.currentTimeMillis())
      shardedAccounts ! Commit(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Commit(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      timers.cancelAll()
      timers.startSingleTimer('WaitingForCommitAcks, TimedOut(moneyTransaction.transactionId), getTimeOutForCommitPhase)
      log.info("received two yes votes and response from journal, moving forward")
      context.become(commitPhase, discardOld = true)

    case Check if yesVotes.size == 2 && phase != 'Initiated =>
      log.info("received two yes votes but no response from journal, waiting")

    case Check if phase == 'Initiated && yesVotes.size != 2 =>
      log.info("received response from journal but still waiting for votes")


  }

  val commitAcknowledgementsReceived: mutable.Set[AccountId] = mutable.Set[AccountId]()

  def commitPhase: Receive = {

    case AckCommit(accountId, transactionId) =>
      assert(transactionId == moneyTransaction.transactionId)
      assert(accountId == moneyTransaction.sourceAccountId || accountId == moneyTransaction.destinationAccountId)
      log.info("received ack for my commit message")
      commitAcknowledgementsReceived += accountId
      self ! Check

    case Check =>
      if (commitAcknowledgementsReceived.size == 2) {
        commitTimer ! StopTimer(self.path.name, System.currentTimeMillis())
        log.info("received two acks for my commit messages, moving forward")
        timers.cancelAll()
        self ! StartFinalize
        context.become(finalizing, discardOld = true)
      }

    case TimedOut(transactionId) =>
      commitTimer ! StopTimer(self.path.name, System.currentTimeMillis())
      assert(transactionId == moneyTransaction.transactionId)
      log.info("timed out while waiting for acks for my commit messages, now rolling back")
      self ! StartRollback
      context.become(rollingBack, discardOld = true)
  }


  val ackFinalized: mutable.Set[AccountId] = mutable.Set()

  def finalizing: Receive = {

    case StartFinalize =>
      log.info("sending finalize messages with at least once delivery")
      persistAsync(Finalizing(moneyTransaction.transactionId, moneyTransaction.sourceAccountId, moneyTransaction.destinationAccountId)){
        e => onEvent(e)
      }

    case ackFinalize @ AckFinalize(accountId, transactionId, _) =>
      assert(accountId == moneyTransaction.sourceAccountId || accountId == moneyTransaction.destinationAccountId)
      assert(transactionId == moneyTransaction.transactionId)
      log.info("received ack for finalize message")
      persistAsync(ackFinalize) { e =>
        onEvent(e)
        self ! Check
      }

    case Check =>
      if (ackFinalized.size == 2) {
        log.info("received two acks for finalize messages")
        replyTo ! Accepted(Some(moneyTransaction.transactionId))
        context.stop(self)
      }
  }

  var ackRollBacked: Set[AccountId] = Set()
  def rollingBack: Receive = {

    case StartRollback =>
      log.info("sending rollback messages with at least once delivery")
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.sourceAccountId, moneyTransaction, id))
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.destinationAccountId, moneyTransaction, id))
      persistAsync(Rollingback(moneyTransaction.transactionId, moneyTransaction.sourceAccountId, moneyTransaction.destinationAccountId))(_ => ())

    case ackRollback @ AckRollback(accountId, transactionId, _) =>
      assert(accountId == moneyTransaction.sourceAccountId || accountId == moneyTransaction.destinationAccountId)
      assert(transactionId == moneyTransaction.transactionId)
      log.info("received ack for rollback message")
      persistAsync(ackRollback) { e =>
        onEvent(e)
        self ! Check
      }

    case Check =>
      if (ackRollBacked.size == 2) {
        log.info("received two acks for rollback messages")
        replyTo ! Rejected(Some(moneyTransaction.transactionId))
        context.stop(self)
      }
  }

  def onEvent(e: Any): Unit = e match {

    case transaction: MoneyTransaction =>
      this.moneyTransaction = transaction

    case Finalizing(_, _, _) =>
      phase = 'Finalizing
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.sourceAccountId, moneyTransaction, id))
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.destinationAccountId, moneyTransaction, id))

    case AckFinalize(accountId, _, deliveryId) =>
      confirmDelivery(deliveryId)
      ackFinalized += accountId

    case AckRollback(accountId, _, deliveryId) =>
      confirmDelivery(deliveryId)
      ackRollBacked += accountId

    case Rollingback(_, _, _) =>
      phase = 'Rollingback
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.sourceAccountId, moneyTransaction, id))
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.destinationAccountId, moneyTransaction, id))

  }

  override def receiveRecover: Receive = {

    case e: MoneyTransaction => onEvent(e)
    case e: Finalizing => onEvent(e)
    case e: AckFinalize => onEvent(e)
    case e: AckRollback => onEvent(e)
    case e: Rollingback => onEvent(e)

    case RecoveryCompleted =>

      if (phase == 'Finalizing) {
        self ! Check
        context.become(finalizing)
      }

      if (phase == 'Rollingback) {
        self ! Check
        context.become(rollingBack)
      }

      if (phase == 'Initiated) {
        self ! StartRollback
        context.become(rollingBack)
      }

  }

  override def persistenceId: String = self.path.name
}