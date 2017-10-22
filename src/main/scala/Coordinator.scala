package app

import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Timers}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId, Passivate}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import app.messages._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps


/*


See COORDINATOR.txt for the state machine diagram


 */

case object TimedOut

case object Check

case object StartFinalize

case object StartRollback

case object StartVotingProcess

sealed trait Phase
object Phases {
  case object Initiated extends Phase
  case object Finalizing extends Phase
  case object Rollingback extends Phase
}

object TransactionStatusTable {

  val NotStarted = 0
  val InProgress = 1
  val Success = 2
  val Failed = 3

  def toString(s: Int) = {
    case 0 => "not started"
    case 1 => "in progress"
    case 2 => "success"
    case 3 => "failed"
  }

}

object CTimers {

  case object WaitingForVotes

  case object WaitingForCommitment

}

object Coordinator {

  val NumShards: Int = sys.env.get("NUM_SHARDS_COORD").map(_.toInt).getOrElse(100)

  val PassivateAfter: Int = sys.env.get("PASSIVATE_COORD").map(_.toInt).getOrElse(10)

  val TimeOutForVotingPhase: FiniteDuration = sys.env.get("VOTING_TIMEOUT").map(_.toLong milliseconds).getOrElse(600 milliseconds)

  val TimeOutForCommitPhase: FiniteDuration = sys.env.get("COMMIT_TIMEOUT").map(_.toLong milliseconds).getOrElse(600 milliseconds)

  val extractEntityId: ExtractEntityId = {
    case mt @ MoneyTransaction(transactionId, _, _, _, _) => (transactionId, mt)
    case gts @ GetTransactionStatus(transactionId) => (transactionId, gts)
  }

  val extractShardId: ExtractShardId = {
    case MoneyTransaction(transactionId, _, _, _, _) => (Math.abs(transactionId.hashCode) % NumShards).toString
    case GetTransactionStatus(transactionId) => (Math.abs(transactionId.hashCode) % NumShards).toString
  }

  def coordinatorShardRegion(system: ActorSystem, accountsShardRegion: ActorRef): ActorRef = ClusterSharding(system).start(
    typeName = "Coordinator",
    entityProps = Props(new Coordinator(accountsShardRegion)),
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)


  def proxyToShardRegion(system: ActorSystem): ActorRef = ClusterSharding(system).startProxy(
    typeName = "Coordinator",
    role = None,
    extractEntityId = extractEntityId,
    extractShardId = extractShardId
  )

  type AccountId = String

}

class Coordinator(accountsShardRegion: ActorRef) extends PersistentActor with ActorLogging with Timers with AtLeastOnceDelivery {

  import Coordinator._

  var success = false
  var phase: Phase = _
  var moneyTransaction: MoneyTransaction = _
  var replyTo: Option[ActorRef] = None

  context.setReceiveTimeout(Coordinator.PassivateAfter.seconds)

  override def receiveCommand: Receive = {

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)
    case Stop => context.stop(self)

    case GetTransactionStatus(_) =>
      sender() ! TransactionStatus(TransactionStatusTable.NotStarted)

    case transaction: MoneyTransaction =>
      persistAsync(transaction) { _ =>
          phase = Phases.Initiated
          self ! Check
        }
      onEvent(transaction)
      if (transaction.replyToSender) {
        replyTo = Some(sender())
      }
      self ! StartVotingProcess

    case StartVotingProcess =>
      val mt = moneyTransaction
      accountsShardRegion ! Vote(mt.destinationAccountId, mt.transactionId, mt.sourceAccountId, mt.destinationAccountId, mt.amount)
      accountsShardRegion ! Vote(mt.sourceAccountId, mt.transactionId, mt.sourceAccountId, mt.destinationAccountId, mt.amount)
      timers.startSingleTimer(CTimers.WaitingForVotes, TimedOut, TimeOutForVotingPhase)
      context.become(waitingForVoteResults, discardOld = true)
  }

  val yesVotes: mutable.Set[AccountId] = mutable.Set[AccountId]()

  def waitingForVoteResults: Receive = {

    case GetTransactionStatus(_) =>
      sender() ! TransactionStatus(TransactionStatusTable.InProgress)

    case AccountStashOverflow(someAccountId) =>
      self ! No(someAccountId)

    case No(_) | TimedOut =>
      replyTo.foreach(_ ! Rejected(moneyTransaction.transactionId))
      accountsShardRegion ! Abort(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      accountsShardRegion ! Abort(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      timers.cancel(CTimers.WaitingForVotes)
      success = false
      context.become(done)

    case Yes(accId) =>
      yesVotes += accId
      self ! Check

    case Check if yesVotes.size == 2 && phase == Phases.Initiated =>
      accountsShardRegion ! Commit(moneyTransaction.destinationAccountId, moneyTransaction.transactionId)
      accountsShardRegion ! Commit(moneyTransaction.sourceAccountId, moneyTransaction.transactionId)
      timers.cancel(CTimers.WaitingForVotes)
      timers.startSingleTimer(CTimers.WaitingForCommitment, TimedOut, TimeOutForCommitPhase)
      context.become(commitPhase, discardOld = true)

  }

  val commitAcknowledgementsReceived: mutable.Set[AccountId] = mutable.Set[AccountId]()

  def commitPhase: Receive = {

    case GetTransactionStatus =>
      sender ! TransactionStatus(TransactionStatusTable.InProgress)

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

    case GetTransactionStatus =>
      sender() ! TransactionStatus(TransactionStatusTable.InProgress)

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
        replyTo.foreach(_ ! Accepted(moneyTransaction.transactionId))
        success = true
        context.become(done)
      }
  }

  var ackRollBacked: Set[AccountId] = Set()

  def rollingBack: Receive = {

    case GetTransactionStatus =>
      sender() ! TransactionStatus(TransactionStatusTable.InProgress)

    case StartRollback =>
      deliver(accountsShardRegion.path)((id: Long) => Rollback(moneyTransaction.sourceAccountId, moneyTransaction.transactionId, id))
      deliver(accountsShardRegion.path)((id: Long) => Rollback(moneyTransaction.destinationAccountId, moneyTransaction.transactionId, id))
      persistAsync(Rollingback())(_ => ())

    case ackRollback@AckRollback(_, _) =>
      persistAsync(ackRollback) { e =>
        onEvent(e)
        self ! Check
      }

    case Check =>
      if (ackRollBacked.size == 2) {
        replyTo.foreach(_ ! Rejected(moneyTransaction.transactionId))
        success = false
        context.become(done)
      }
  }

  def done: Receive = {

    case GetTransactionStatus(_) =>
      if (success) sender() ! TransactionStatus(TransactionStatusTable.Success) else
        sender() ! TransactionStatus(TransactionStatusTable.Failed)

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)

    case Stop => context.stop(self)

  }




  def onEvent(e: Any): Unit = e match {

    case transaction: MoneyTransaction =>
      this.moneyTransaction = transaction

    case f: Finalizing =>
      phase = Phases.Finalizing
      deliver(accountsShardRegion.path)((id: Long) => Finalize(moneyTransaction.sourceAccountId, moneyTransaction.transactionId, id))
      deliver(accountsShardRegion.path)((id: Long) => Finalize(moneyTransaction.destinationAccountId, moneyTransaction.transactionId, id))

    case AckFinalize(accountId, deliveryId) =>
      confirmDelivery(deliveryId)
      ackFinalized += accountId

    case AckRollback(accountId, deliveryId) =>
      confirmDelivery(deliveryId)
      ackRollBacked += accountId

    case r: Rollingback =>
      phase = Phases.Rollingback
      deliver(accountsShardRegion.path)((id: Long) => Rollback(moneyTransaction.sourceAccountId, moneyTransaction.transactionId, id))
      deliver(accountsShardRegion.path)((id: Long) => Rollback(moneyTransaction.destinationAccountId, moneyTransaction.transactionId, id))

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