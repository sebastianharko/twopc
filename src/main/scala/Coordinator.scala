package app

import akka.actor.{ActorLogging, ActorRef, Timers}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import akka.testkit.TestActors

import scala.concurrent.duration._

case class MoneyTransaction(transactionId: String,
    sourceAccountId: String,
    destinationAccountId: String,
    amount: Int)

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

  val VotingPhaseTimesOutAfter = 1 seconds

  val WaitingForCommitPhaseTimesOutAfter = 1 second

}

class Coordinator(shardedAccounts: ActorRef) extends PersistentActor with ActorLogging with Timers with AtLeastOnceDelivery {

  import Coordinator._

  var phase: Any = null

  var moneyTransaction: MoneyTransaction = null
  var replyTo: ActorRef = context.actorOf(TestActors.blackholeProps)

  override def receiveCommand = {
    case transaction: MoneyTransaction =>
      persistAsync(transaction)(_ => phase = 'Initiated)
      onEvent(transaction)
      this.replyTo = sender()
      self ! StartVotingProcess
    case StartVotingProcess =>
      shardedAccounts ! Vote(moneyTransaction.sourceAccountId, moneyTransaction)
      shardedAccounts ! Vote(moneyTransaction.destinationAccountId, moneyTransaction)
      timers.startSingleTimer('WaitingForVotes, TimedOut(moneyTransaction.transactionId), VotingPhaseTimesOutAfter)
      context.become(waitingForVoteResults, discardOld = true)
      log.info("started voting process")
    }

  var yesVotes: Set[AccountId] = Set()

  def waitingForVoteResults: Receive = {

    case TimedOut(_) =>
      replyTo ! Rejected(Some(moneyTransaction.transactionId))
      shardedAccounts ! Abort(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      timers.cancelAll()
      log.info("timed out (voting process)")
      context.stop(self)

    case No(_) =>
      replyTo ! Rejected(Some(moneyTransaction.transactionId))
      shardedAccounts ! Abort(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      timers.cancelAll()
      log.info("one of the participants voted no")
      context.stop(self)

    case Yes(accId) =>
      log.info("received yes vote")
      yesVotes = yesVotes + accId
      self ! Check

    case Check if yesVotes.size == 2 && phase == 'Initiated => {
        shardedAccounts ! Commit(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
        shardedAccounts ! Commit(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
        timers.cancelAll()
        timers.startSingleTimer('WaitingForCommitAcks, TimedOut(moneyTransaction.transactionId), WaitingForCommitPhaseTimesOutAfter)
        log.info("received two yes votes and response from journal, moving forward")
        context.become(waitingForCommitAcks, discardOld = true)
      }

    case Check if yesVotes.size == 2 =>
      log.info("received two yes votes but no response from journal yet, let's check again later")
      timers.startSingleTimer('CheckAgain, Check, VotingPhaseTimesOutAfter / 8)

  }

  var commitAcksReceived: Set[AccountId] = Set()
  def waitingForCommitAcks: Receive = {

    case AckCommit(accountId, _) =>
      log.info("received ack for my commit message")
      commitAcksReceived = commitAcksReceived + accountId
      self ! Check

    case Check =>
      if (commitAcksReceived.size == 2) {
        log.info("received two acks for my commit messages, moving forward")
        timers.cancelAll()
        self ! StartFinalize
        context.become(finalizing, discardOld = true)
      }

    case TimedOut(_) =>
      log.info("timed out while waiting for acks for my commit messages, now rolling back")
      self ! StartRollback
      context.become(rollingBack, discardOld = true)
  }

  var ackFinalized: Set[AccountId] = Set()
  def finalizing: Receive = {
    case StartFinalize =>
      log.info("sending finalize messages with at least once delivery")
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.sourceAccountId, moneyTransaction, id))
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.destinationAccountId, moneyTransaction, id))
      persistAsync(Finalizing(moneyTransaction.transactionId, moneyTransaction.sourceAccountId, moneyTransaction.destinationAccountId))(_ => ())
    case ackFinalize @ AckFinalize(_, _, _) =>
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
      log.info("sending rollback messages")
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.sourceAccountId, moneyTransaction, id))
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.destinationAccountId, moneyTransaction, id))
      persistAsync(Rollingback(moneyTransaction.transactionId, moneyTransaction.sourceAccountId, moneyTransaction.destinationAccountId))(_ => ())
    case ackRollback @ AckRollback(_, _, _) =>
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

  def onEvent(e: Any) = e match {

    case transaction: MoneyTransaction =>
      this.moneyTransaction = transaction

    case Finalizing(_, _, _) =>
      phase = 'Finalizing
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.sourceAccountId, moneyTransaction, id))
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.destinationAccountId, moneyTransaction, id))

    case AckFinalize(accountId, _, deliveryId) =>
      confirmDelivery(deliveryId)
      ackFinalized = ackFinalized + accountId

    case AckRollback(accountId, _, deliveryId) =>
      confirmDelivery(deliveryId)
      ackRollBacked = ackRollBacked + accountId

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