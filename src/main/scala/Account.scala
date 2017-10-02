package app

import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props, Stash, Timers}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.concurrent.duration._

case class Deposit(accountId: String, amount: Int)

case class Withdraw(accountId: String, amount: Int)

case class BalanceChanged(amount: Int)

case class Query(accountId: String)

case class IsLocked(accountId: String)

object Sharding {

  val NumShards = 60

  val extractEntityId: ExtractEntityId = {
    case c @ Deposit(accountId, _) => (accountId, c)
    case c @ Withdraw(accountId, _) => (accountId, c)
    case c @ Query(accountId) => (accountId, c)
    case c @ IsLocked(accountId) => (accountId, c)
    case c @ Vote(accountId, _) => (accountId, c)
    case c @ Commit(accountId, _) => (accountId, c)
    case c @ Abort(accountId, _) => (accountId, c)
    case c @ Rollback(accountId, _, _) => (accountId, c)
    case c @ Finalize(accountId, _, _) => (accountId, c)
  }

  val extractShardId: ExtractShardId = {
    case c @ Deposit(accountId, _) => (accountId.hashCode % NumShards).toString
    case c @ Withdraw(accountId, _) => (accountId.hashCode % NumShards).toString
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





class AccountActor extends PersistentActor with ActorLogging with Timers with Stash {

  override def persistenceId: String = self.path.name

  val accountId = self.path.name

  var previousBalance = 0

  var balance = 0

  var rollbackedTransactions: Set[String] = Set()

  var finalizedTransactions: Set[String] = Set()


  def validate(e: Any): Boolean = {
    e match {
      case Withdraw(id, m) =>
        m <= balance && accountId == id
      case Deposit(id, m) =>
        accountId == id
    }
  }

  def onEvent(e: Any) = {
    e match {
      case Finalize(_, transaction, _) =>
        finalizedTransactions = finalizedTransactions + transaction.id
      case Rollback(_, transaction, _) =>
        rollbackedTransactions = rollbackedTransactions + transaction.id
      case BalanceChanged(delta) =>
        previousBalance = balance
        balance = balance + delta
      case _ => // do nothing
    }
  }

  override def receiveCommand = {

    case r: Rollback if rollbackedTransactions.contains(r.transaction.id) =>
      sender() ! AckRollback(accountId, r.transaction.id)

    case f: Finalize if finalizedTransactions.contains(f.transaction.id) =>
      sender() ! AckFinalize(accountId, f.transaction.id)

    case Query(_) =>
        sender() ! balance

    case IsLocked(_) =>
        sender() ! false

    case c @ Deposit(_, m) =>
      if (validate(c)) {
        persist(BalanceChanged(m)) {
          e => {
            onEvent(e)
            sender() ! Accepted
          }
        }
      } else {
        sender() ! Rejected
      }

    case c @ Withdraw(_, m) =>
      if (!validate(c)) {
        sender() ! Rejected
      } else {
        persist(BalanceChanged(-m)) {
          event => {
            onEvent(event)
            sender() ! Accepted
          }
        }
      }

    case Vote(_, moneyTransaction: MoneyTransaction) =>

      val valid = if (moneyTransaction.sourceId == accountId)
          validate(Withdraw(accountId, moneyTransaction.amount))
        else
          validate(Deposit(accountId, moneyTransaction.amount))

      if (!valid) {
        sender() ! No(accountId)
      } else {
        sender() ! Yes(accountId)
        timers.startSingleTimer(0, TimedOut(moneyTransaction.id), 400 milliseconds)
        context.become(waitingForCommitOrAbortOrTimeout(moneyTransaction))
      }

  }


  def waitingForCommitOrAbortOrTimeout(transaction: MoneyTransaction): Receive = {

    case r: Rollback if rollbackedTransactions.contains(r.transaction.id) =>
      sender() ! AckRollback(accountId, r.transaction.id)

    case f: Finalize if finalizedTransactions.contains(f.transaction.id) =>
      sender() ! AckFinalize(accountId, f.transaction.id)

    case Query(_) => sender() ! balance

    case IsLocked(_) => sender() ! true

    case m: Withdraw =>
      stash()

    case m: Deposit =>
      stash()

    case Abort(_, tId) if tId == transaction.id =>
      // don't worry about sending an Ack here, since the coordinator can assume it's gonna time out anyway
      unstashAll()
      context.become(receive, discardOld = true)

    case TimedOut(tId) if tId == transaction.id =>
      unstashAll()
      context.become(receive, discardOld = true)

    case Commit(_, tId: String) if tId == transaction.id =>
      val event = if (transaction.sourceId == accountId) BalanceChanged(-transaction.amount) else BalanceChanged(transaction.amount)
      persistAll(List(transaction, event)) {
        case t: MoneyTransaction => // do nothing
        case e: BalanceChanged => {
          sender() ! AckCommit(accountId, tId)
          onEvent(e)
          timers.cancel(0)
          context.become(waitingForFinalizeOrRollback(transaction), discardOld = true)
        }
      }


  }


  def waitingForFinalizeOrRollback(transaction: MoneyTransaction): Receive = {

    case r: Rollback if rollbackedTransactions.contains(r.transaction.id) =>
      sender() ! AckRollback(accountId, r.transaction.id)

    case f: Finalize if finalizedTransactions.contains(f.transaction.id) =>
      sender() ! AckFinalize(accountId, f.transaction.id)

    case Query(_) => sender() ! previousBalance // not finalized so previous balance is still in effect

    case IsLocked(_) => sender() ! true

    case m: Withdraw =>
      stash()

    case m: Deposit =>
      stash()

    case finalize @ Finalize(_, t, _) if t.id == transaction.id =>
     persist(finalize) {
       _ => {
         sender() ! AckFinalize(accountId, t.id)
         onEvent(finalize)
         unstashAll()
         context.become(receive, discardOld = true)
       }
     }

    case rollback @ Rollback(_, t, _) if t.id == transaction.id =>
      val counterEvent = if (transaction.sourceId == accountId) BalanceChanged(transaction.amount) else BalanceChanged(-transaction.amount)
      persistAll(List(counterEvent, rollback)) {
        case e: BalanceChanged => {
          onEvent(e)
        }
        case r: Rollback => {
          sender() ! AckRollback(accountId, t.id)
          onEvent(rollback)
          unstashAll()
          context.become(receive, discardOld = true)
        }
      }

  }

  var uncompletedTransaction: MoneyTransaction = null

  override def receiveRecover: Receive = {

    case mt: MoneyTransaction =>
      uncompletedTransaction = mt

    case r: Rollback =>
      onEvent(r)
      uncompletedTransaction = null

    case f: Finalize =>
      onEvent(f)
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


