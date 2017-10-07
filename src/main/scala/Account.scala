package app

import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Stash, Timers}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.concurrent.duration._

case class ChangeBalance(accountId: String, byAmount: Int)

case class BalanceChanged(amount: Int)

case class Query(accountId: String)

case class IsLocked(accountId: String)

object Sharding {

  val NumShards = 60

  val extractEntityId: ExtractEntityId = {
    case c @ ChangeBalance(accountId, _) => (accountId, c)
    case c @ Query(accountId) => (accountId, c)
    case c @ IsLocked(accountId) => (accountId, c)
    case c @ Vote(accountId, _) => (accountId, c)
    case c @ Commit(accountId, _) => (accountId, c)
    case c @ Abort(accountId, _) => (accountId, c)
    case c @ Rollback(accountId, _, _) => (accountId, c)
    case c @ Finalize(accountId, _, _) => (accountId, c)
  }

  val extractShardId: ExtractShardId = {
    case c @ ChangeBalance(accountId, _) => (accountId.hashCode % NumShards).toString
    case c @ Query(accountId) => (accountId.hashCode % NumShards).toString
    case c @ IsLocked(accountId) => (accountId.hashCode % NumShards).toString
    case c @ Vote(accountId, _) => (accountId.hashCode % NumShards).toString
    case c @ Commit(accountId, _) => (accountId.hashCode % NumShards).toString
    case c @ Abort(accountId, _) => (accountId.hashCode % NumShards).toString
    case c @ Rollback(accountId, _, _) => (accountId.hashCode % NumShards).toString
    case c @ Finalize(accountId, _, _) => (accountId.hashCode % NumShards).toString
  }

  def accounts(system: ActorSystem): ActorRef = ClusterSharding(system).start(
    typeName = "Account",
    entityProps = Props(new AccountActor()).withMailbox("stash-capacity-mailbox"),
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)
}


object AccountActor {

  val CommitOrAbortTimeout = 400.milliseconds

}

case object Stop

class AccountActor extends PersistentActor with ActorLogging with Timers with Stash {

  import akka.cluster.sharding.ShardRegion.Passivate

  context.setReceiveTimeout(120.seconds)

  override def persistenceId: String = self.path.name

  val accountId = self.path.name

  var previousBalance = 0

  var balance = 0

  var coordinator: ActorRef = null

  def validate(e: Any): Boolean = {
    e match {
      case ChangeBalance(_, byAmount) => balance + byAmount >= 0
    }
  }

  def validateMoneyTransaction(moneyTransaction: MoneyTransaction) = {

    if (moneyTransaction.sourceAccountId == accountId)
      validate(ChangeBalance(accountId, -moneyTransaction.amount))
    else
      validate(ChangeBalance(accountId, moneyTransaction.amount))

  }


  def onEvent(e: Any) = {
    e match {
      case BalanceChanged(delta) =>
        previousBalance = balance
        balance = balance + delta
    }
  }

  def changingBalance: Receive = {

    case Query(accId) if accId == accountId =>
      sender() ! balance

    case IsLocked(accId) if accId == accountId =>
      sender() ! true

    case c @ ChangeBalance(accId, m) if accId == accountId && !validate(c) =>
      sender() ! Rejected()

    case c @ ChangeBalance(accId, m) if accId == accountId && validate(c) =>
      stash()

    case Vote(accId, moneyTransaction: MoneyTransaction) if accId == accountId && !validateMoneyTransaction(moneyTransaction) =>
      sender() ! No(accountId)

    case Vote(accId, moneyTransaction: MoneyTransaction) if accId == accountId =>
      stash()

    case r: Rollback if r.accountId == accountId =>
      sender() ! AckRollback(r.accountId, r.transaction.transactionId, r.deliveryId)

    case f: Finalize if f.accountId == accountId =>
      sender() ! AckFinalize(accountId, f.transaction.transactionId, f.deliveryId)

  }

  var replyTo: ActorRef = null // for ChangeBalance

  override def receiveCommand = {

    case Query(accId) if accId == accountId =>
        sender() ! balance

    case IsLocked(accId) if accId == accountId =>
        sender() ! false

    case c @ ChangeBalance(accId, m) if accId == accountId && validate(c) =>
        replyTo = sender()
        context.become(changingBalance, discardOld = true)
        persistAsync(BalanceChanged(m)) {
          e => {
            onEvent(e)
            replyTo ! Accepted()
            unstashAll()
            context.become(receiveCommand, discardOld = true)
          }
        }

    case c @ ChangeBalance(accId, m) if accId == accountId && !validate(c) =>
      sender() ! Rejected()

    case Vote(accId, moneyTransaction: MoneyTransaction) if accId == accountId && validateMoneyTransaction(moneyTransaction)=> {
        coordinator = sender()
        coordinator ! Yes(accountId)
        timers.startSingleTimer(0, TimedOut(moneyTransaction.transactionId), AccountActor.CommitOrAbortTimeout)
        context.become(waitingForCommitOrAbortOrTimeout(moneyTransaction))
    }

    case Vote(accId, moneyTransaction: MoneyTransaction) if accId == accountId && !validateMoneyTransaction(moneyTransaction) =>
      sender() ! No(accountId)

    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)

    case Stop => context.stop(self)

    case r: Rollback if r.accountId == accountId =>
      sender() ! AckRollback(r.accountId, r.transaction.transactionId, r.deliveryId)

    case f: Finalize if f.accountId == accountId =>
      sender() ! AckFinalize(accountId, f.transaction.transactionId, f.deliveryId)

  }


  def waitingForCommitOrAbortOrTimeout(transaction: MoneyTransaction): Receive = {

    case Query(accId) if accId == accountId => sender() ! balance

    case IsLocked(accId) if accId == accountId => sender() ! true

    case c: ChangeBalance if c.accountId == accountId =>
      stash()

    case Abort(accId, transId) if accId == accountId && transId == transaction.transactionId =>
      // don't worry about sending an Ack here, since the coordinator can assume it's gonna time out anyway
      timers.cancelAll()
      coordinator = null
      unstashAll()
      context.become(receiveCommand, discardOld = true)

    case TimedOut(transId) if transId == transaction.transactionId =>
      coordinator = null
      unstashAll()
      context.become(receiveCommand, discardOld = true)

    // Commit is delivered at most once
    case Commit(accId, tId: String) if accId == accountId && tId == transaction.transactionId =>
      timers.cancelAll()
      val event = if (transaction.sourceAccountId == accountId) BalanceChanged(-transaction.amount) else BalanceChanged(transaction.amount)
      persistAllAsync(List(transaction, event)) {
        case t: MoneyTransaction => // do nothing
        case e: BalanceChanged => {
          context.become(waitingForFinalizeOrRollback(transaction), discardOld = true)
          onEvent(e)
          coordinator ! AckCommit(accountId, tId)
        }
      }

    case r: Rollback if r.accountId == accountId && r.transaction.transactionId != transaction.transactionId =>
      sender() ! AckRollback(r.accountId, r.transaction.transactionId, r.deliveryId)

    case f: Finalize if f.accountId == accountId && f.transaction.transactionId != transaction.transactionId =>
      sender() ! AckFinalize(f.accountId, f.transaction.transactionId, f.deliveryId)

  }

  var persistInProgress = false
  def waitingForFinalizeOrRollback(transaction: MoneyTransaction): Receive = {

    case Query(accId) if accId == accountId => sender() ! previousBalance // not finalized so previous balance is still in effect

    case IsLocked(accId) if accId == accountId => sender() ! true

    case m: ChangeBalance if m.accountId == accountId =>
      stash()

    case finalize @ Finalize(accId, t, deliveryId) if !persistInProgress && t.transactionId == transaction.transactionId && accId == accountId =>

      if (coordinator == null)
        coordinator = sender()

      persistInProgress = true

      persistAsync(finalize) {
         _ => {
           persistInProgress = false
           coordinator ! AckFinalize(accountId, transaction.transactionId, deliveryId)
           coordinator = null
           unstashAll()
           context.become(receiveCommand, discardOld = true) // going back to normal
        }
      }

    case rollback @ Rollback(_, t, _) if t.transactionId == transaction.transactionId =>

      if (coordinator == null)
        coordinator = sender()

      val counterEvent = if (transaction.sourceAccountId == accountId) BalanceChanged(transaction.amount) else BalanceChanged(-transaction.amount)

      persistInProgress = true

      persistAllAsync(List(counterEvent, rollback)) {
        case e: BalanceChanged => {
          onEvent(e)
        }
        case r: Rollback => {
          persistInProgress = false
          coordinator ! AckRollback(accountId, t.transactionId, r.deliveryId)
          coordinator = null
          unstashAll()
          context.become(receiveCommand, discardOld = true) // going back to normal
        }
      }


    case r: Rollback if r.transaction.transactionId != transaction.transactionId && r.accountId == accountId =>
      sender() ! AckRollback(r.accountId, r.transaction.transactionId, r.deliveryId)

    case f: Finalize if f.transaction.transactionId != transaction.transactionId && f.accountId == accountId =>
      sender() ! AckFinalize(f.accountId, f.transaction.transactionId, f.deliveryId)

  }

  var uncompletedTransaction: MoneyTransaction = null

  override def receiveRecover: Receive = {

    case mt: MoneyTransaction =>
      uncompletedTransaction = mt

    case r: Rollback =>
      uncompletedTransaction = null

    case f: Finalize =>
      uncompletedTransaction = null

    case e @ BalanceChanged(_) =>
      onEvent(e)

    case RecoveryCompleted =>
      if (uncompletedTransaction == null)
        log.info(s"recovery complete, balance is $balance")
      else {
        context.become(waitingForFinalizeOrRollback(uncompletedTransaction))
      }
  }



}


