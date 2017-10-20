package app

import akka.actor.{ActorLogging, ActorRef, Timers}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import app.messages._

import scala.collection.mutable
import scala.concurrent.duration._
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

case object TimedOut

case object Check

case object StartFinalize

case object StartRollback

case object StartVotingProcess

object Phases {
  val Initiated = "Initiated"
  val Finalizing = "Finalizing"
  val Rollingback = "Rollingback"
}

object CTimers {

  val WaitingForVotes = "WaitingForVotes"

  val WaitingForCommitment = "WaitingForCommitment"

}

object Coordinator {

  type AccountId = String

  val TimeOutForVotingPhase = sys.env.get("VOTING_TIMEOUT").map(_.toLong milliseconds).getOrElse(400 milliseconds)

  val TimeOutForCommitPhase = sys.env.get("COMMIT_TIMEOUT").map(_.toLong milliseconds).getOrElse(400 milliseconds)

}

class Coordinator(shardedAccounts: ActorRef) extends PersistentActor with ActorLogging with Timers with AtLeastOnceDelivery {

  import Coordinator._

  var phase: String = _
  var moneyTransaction: MoneyTransaction = _
  var replyTo: ActorRef = _

  override def receiveCommand: Receive = {

    case transaction: MoneyTransaction =>
      persistAsync(transaction) { _ =>
          phase = Phases.Initiated
          self ! Check
        }
      onEvent(transaction)
      this.replyTo = sender()
      self ! StartVotingProcess

    case StartVotingProcess =>
      val mt = moneyTransaction
      shardedAccounts ! Vote(mt.destinationAccountId, mt.transactionId, mt.sourceAccountId, mt.destinationAccountId, mt.amount)
      shardedAccounts ! Vote(mt.sourceAccountId, mt.transactionId, mt.sourceAccountId, mt.destinationAccountId, mt.amount)
      timers.startSingleTimer(CTimers.WaitingForVotes, TimedOut, TimeOutForVotingPhase)
      context.become(waitingForVoteResults, discardOld = true)
  }

  val yesVotes: mutable.Set[AccountId] = mutable.Set[AccountId]()

  def waitingForVoteResults: Receive = {

    case TimedOut =>
      replyTo ! Rejected(moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      context.stop(self)

    case AccountStashOverflow(someAccountId) =>
      self ! No(someAccountId)

    case No(_) =>
      replyTo ! Rejected(moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Abort(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      timers.cancel(CTimers.WaitingForVotes)
      context.stop(self)

    case Yes(accId) =>
      yesVotes += accId
      self ! Check

    case Check if yesVotes.size == 2 && phase == Phases.Initiated =>
      shardedAccounts ! Commit(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      shardedAccounts ! Commit(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      timers.cancel(CTimers.WaitingForVotes)
      timers.startSingleTimer(CTimers.WaitingForCommitment, TimedOut, TimeOutForCommitPhase)
      context.become(commitPhase, discardOld = true)

  }

  val commitAcknowledgementsReceived: mutable.Set[AccountId] = mutable.Set[AccountId]()

  def commitPhase: Receive = {

    case AckCommit(accountId) =>
      commitAcknowledgementsReceived += accountId
      self ! Check

    case Check =>
      if (commitAcknowledgementsReceived.size == 2) {
        timers.cancel(CTimers.WaitingForCommitment)
        self ! StartFinalize
        context.become(finalizing, discardOld = true)
      }

    case TimedOut =>
      self ! StartRollback
      context.become(rollingBack, discardOld = true)
  }


  val ackFinalized: mutable.Set[AccountId] = mutable.Set()

  def finalizing: Receive = {

    case StartFinalize =>
      persistAsync(Finalizing()) {
        e => onEvent(e)
      }

    case ackFinalize @ AckFinalize(_,  _) =>
      persistAsync(ackFinalize) { e =>
        onEvent(e)
        self ! Check
      }

    case Check =>
      if (ackFinalized.size == 2) {
        replyTo ! Accepted(moneyTransaction.transactionId)
        context.stop(self)
      }
  }

  var ackRollBacked: Set[AccountId] = Set()

  def rollingBack: Receive = {

    case StartRollback =>
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.sourceAccountId, moneyTransaction.transactionId, id))
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.destinationAccountId, moneyTransaction.transactionId, id))
      persistAsync(Rollingback())(_ => ())

    case ackRollback@AckRollback(_, _) =>
      persistAsync(ackRollback) { e =>
        onEvent(e)
        self ! Check
      }

    case Check =>
      if (ackRollBacked.size == 2) {
        replyTo ! Rejected(moneyTransaction.transactionId)
        context.stop(self)
      }
  }

  def onEvent(e: Any): Unit = e match {

    case transaction: MoneyTransaction =>
      this.moneyTransaction = transaction

    case f: Finalizing =>
      phase = Phases.Finalizing
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.sourceAccountId, moneyTransaction.transactionId, id))
      deliver(shardedAccounts.path)((id: Long) => Finalize(moneyTransaction.destinationAccountId, moneyTransaction.transactionId, id))

    case AckFinalize(accountId, deliveryId) =>
      confirmDelivery(deliveryId)
      ackFinalized += accountId

    case AckRollback(accountId, deliveryId) =>
      confirmDelivery(deliveryId)
      ackRollBacked += accountId

    case r: Rollingback =>
      phase = Phases.Rollingback
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.sourceAccountId, moneyTransaction.transactionId, id))
      deliver(shardedAccounts.path)((id: Long) => Rollback(moneyTransaction.destinationAccountId, moneyTransaction.transactionId, id))

  }

  override def receiveRecover: Receive = {

    case e: MoneyTransaction => onEvent(e)
    case e: Finalizing => onEvent(e)
    case e: AckFinalize => onEvent(e)
    case e: AckRollback => onEvent(e)
    case e: Rollingback => onEvent(e)

    case RecoveryCompleted =>

      if (phase == Phases.Finalizing) {
        self ! Check
        context.become(finalizing)
      }

      if (phase == Phases.Rollingback) {
        self ! Check
        context.become(rollingBack)
      }

      if (phase == Phases.Initiated) {
        self ! StartRollback
        context.become(rollingBack)
      }

  }

  override def persistenceId: String = self.path.name
}