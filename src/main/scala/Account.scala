package app

import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Stash, Timers}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted, ReplyToStrategy, StashOverflowStrategy}

/*


                                                                                    +----------------------------+
                                    +--------------+                                | mark the                   |
   +-------------+  Votes yes       |              |                                - transaction                |
   |             |  on transaction  |  Read Only   |                               /| as finalized in the journal|
   |   Account   --------------------   Account    |                              / +----------------------------+
   |             |                  |              |        +---------------+    /
   +------|------+                  |              |        |               |   /
          |                         +-------|------+        |  Waiting for  |  /
          |                                 |               |  Rollback     | /
          |                                 |               |  or           |/
          |                                 |               |  Finalize     |\
          |                                 |               |               | \
          |                                 |               +-------|-------+  \
          | on timeout              +-------|------+                |           \  +----------------------------+
          | or abort                |              |                |            \ |                            |
          |                         |  Waiting for |                |             \| write to the journal       |
          |                         |  Commit      |                |              - a counter event            |
          +--------------------------  or          |                |              +----------------------------+
                                    |  Abort       |     ack the    |
                                    |              |     commit     |
                                    +-------------------------------+
                                                     write  to the journal
                                                     (transaction, event)



 */


import scala.concurrent.duration._

case class ChangeBalance(accountId: String, byAmount: Int)

case class BalanceChanged(amount: Int)

case class GetBalance(accountId: String)

case class IsLocked(accountId: String)

case class AccountStashOverflow(accountId: String)

object Sharding {

  val NumShards = 60

  val extractEntityId: ExtractEntityId = {
    case c @ ChangeBalance(accountId, _) => (accountId, c)
    case c @ GetBalance(accountId) => (accountId, c)
    case c @ IsLocked(accountId) => (accountId, c)
    case c @ Vote(accountId, _) => (accountId, c)
    case c @ Commit(accountId, _) => (accountId, c)
    case c @ Abort(accountId, _) => (accountId, c)
    case c @ Rollback(accountId, _, _) => (accountId, c)
    case c @ Finalize(accountId, _, _) => (accountId, c)
  }

  val extractShardId: ExtractShardId = {
    case ChangeBalance(accountId, _) => (accountId.hashCode % NumShards).toString
    case GetBalance(accountId) => (accountId.hashCode % NumShards).toString
    case IsLocked(accountId) => (accountId.hashCode % NumShards).toString
    case Vote(accountId, _) => (accountId.hashCode % NumShards).toString
    case Commit(accountId, _) => (accountId.hashCode % NumShards).toString
    case Abort(accountId, _) => (accountId.hashCode % NumShards).toString
    case Rollback(accountId, _, _) => (accountId.hashCode % NumShards).toString
    case Finalize(accountId, _, _) => (accountId.hashCode % NumShards).toString
  }

  def accounts(system: ActorSystem): ActorRef = ClusterSharding(system).start(
    typeName = "Account",
    entityProps = Props(new AccountActor()).withMailbox("stash-capacity-mailbox"),
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)
}


object AccountActor {

  val CommitOrAbortTimeout: FiniteDuration = sys.env.get("ACCOUNT_TIMEOUT").map(_.toInt).map(_ milliseconds).getOrElse(600 milliseconds)

}

case object Stop

class AccountActor extends PersistentActor with ActorLogging with Timers with Stash {

  override def internalStashOverflowStrategy: StashOverflowStrategy = ReplyToStrategy(AccountStashOverflow(accountId))

  import akka.cluster.sharding.ShardRegion.Passivate

  context.setReceiveTimeout(120.seconds)

  override def persistenceId: String = self.path.name

  val accountId: String = self.path.name

  var previousBalance = 0

  var balance = 0

  var activeBalance = balance


  def validate(e: Any): Boolean = {
    e match {
      case ChangeBalance(_, byAmount) => activeBalance + byAmount >= 0
    }
  }

  def validateMoneyTransaction(moneyTransaction: MoneyTransaction): Boolean = {
    if (moneyTransaction.sourceAccountId == accountId)
      validate(ChangeBalance(accountId, -moneyTransaction.amount))
    else
      validate(ChangeBalance(accountId, moneyTransaction.amount))

  }


  def onEvent(e: Any): Unit = {
    e match {
      case BalanceChanged(delta) =>
        previousBalance = balance
        balance = balance + delta
    }
  }

  def canGetCurrentBalance: Receive = {
    case GetBalance(accId) =>
      sender() ! activeBalance
  }

  def canRespondToPreviousTransactionsCoordinators(currentTransactionId: String): Receive = {
    case r: Rollback if  r.transaction.transactionId != currentTransactionId =>
      sender() ! AckRollback(r.accountId, r.transaction.transactionId, r.deliveryId)

    case f: Finalize if f.transaction.transactionId != currentTransactionId =>
      sender() ! AckFinalize(accountId, f.transaction.transactionId, f.deliveryId)
  }

  def showsLocked: Receive = {
    case IsLocked(accId) =>
      sender() ! true
  }

  def showsNotLocked: Receive = {
    case IsLocked(accId) =>
      sender() ! false
  }

  def canRejectInvalidChangeBalance: Receive = {
    case c @ ChangeBalance(accId, _) if !validate(c) =>
      sender() ! Rejected()
  }

  def canVoteNoOnMoneyTransaction: Receive = {
    case Vote(accId, moneyTransaction: MoneyTransaction) if !validateMoneyTransaction(moneyTransaction) =>
      sender() ! No(accountId)
  }

  def canStashChangeBalanceCommand: Receive = {

    case c@ChangeBalance(accId, _) if validate(c) =>
      stash()
  }

  def canStashVoteRequest: Receive = {
    case Vote(accId, moneyTransaction) if validateMoneyTransaction(moneyTransaction) =>
      stash()
  }

  def passivationSupport: Receive = {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)
    case Stop => context.stop(self)
  }

  var replyTo: ActorRef = _ // for ChangeBalance
  def canChangeBalance: Receive = {
    case c @ ChangeBalance(accId, m) if validate(c) =>
      replyTo = sender()
      context.become(whileChangingBalance, discardOld = true)
      persistAsync(BalanceChanged(m)) {
        e => {
          onEvent(e)
          activeBalance = balance
          replyTo ! Accepted()
          unstashAll()
          context.become(receiveCommand, discardOld = true)
        }
      }
  }

  var coordinator: ActorRef = _
  def canVoteYesOnMoneyTransaction: Receive = {
    case Vote(accId, moneyTransaction: MoneyTransaction) if validateMoneyTransaction(moneyTransaction) =>
      coordinator = sender()
      coordinator ! Yes(accountId)
      timers.startSingleTimer(0, TimedOut(moneyTransaction.transactionId), AccountActor.CommitOrAbortTimeout)
      context.become(waitingForCommitOrAbortOrTimeout(moneyTransaction))
  }

  def whileChangingBalance: Receive = canGetCurrentBalance
      .orElse(canRejectInvalidChangeBalance)
      .orElse(canVoteNoOnMoneyTransaction)
      .orElse(canStashChangeBalanceCommand)
      .orElse(canStashVoteRequest)
      .orElse(showsLocked)
      .orElse(canRespondToPreviousTransactionsCoordinators(null))

  override def receiveCommand: Receive = canGetCurrentBalance
      .orElse(canRejectInvalidChangeBalance)
      .orElse(canChangeBalance)
      .orElse(canVoteNoOnMoneyTransaction)
      .orElse(canVoteYesOnMoneyTransaction)
      .orElse(showsNotLocked)
      .orElse(passivationSupport)
      .orElse(canRespondToPreviousTransactionsCoordinators(null))


  def waitingForCommitOrAbortOrTimeout(transaction: MoneyTransaction): Receive =
    canGetCurrentBalance
        .orElse(canRejectInvalidChangeBalance)
        .orElse(canVoteNoOnMoneyTransaction)
        .orElse(canStashChangeBalanceCommand)
        .orElse(canStashVoteRequest)
        .orElse(canRespondToPreviousTransactionsCoordinators(currentTransactionId = transaction.transactionId))
        .orElse(showsLocked)
        .orElse {

            case Abort(accId, transId) if transId == transaction.transactionId =>
              // don't worry about sending an Ack here, since the coordinator can assume it's gonna time out anyway
              timers.cancelAll()
              coordinator = null
              unstashAll()
              context.become(receiveCommand, discardOld = true)

            case TimedOut(transId) =>
              coordinator = null
              unstashAll()
              context.become(receiveCommand, discardOld = true)

            case Commit(accId, tId: String) if tId == transaction.transactionId =>
              timers.cancelAll()
              val event = if (transaction.sourceAccountId == accountId) BalanceChanged(-transaction.amount) else BalanceChanged(transaction.amount)
              persistAllAsync(List(transaction, event)) {
                case _: MoneyTransaction => // do nothing
                case e: BalanceChanged =>
                  context.become(waitingForFinalizeOrRollback(transaction), discardOld = true)
                  onEvent(e)
                  activeBalance = previousBalance
                  coordinator ! AckCommit(accountId, tId)
              }
        }

  var alreadyReceived = false
  def waitingForFinalizeOrRollback(transaction: MoneyTransaction): Receive =
        canGetCurrentBalance
            .orElse(canRejectInvalidChangeBalance)
            .orElse(canVoteNoOnMoneyTransaction)
            .orElse(canStashChangeBalanceCommand)
            .orElse(canStashVoteRequest)
            .orElse(showsLocked)
            .orElse(canRespondToPreviousTransactionsCoordinators(transaction.transactionId))
            .orElse {
              case finalize @ Finalize(accId, t, deliveryId) if !alreadyReceived && t.transactionId == transaction.transactionId =>
                if (coordinator == null) coordinator = sender()
                alreadyReceived = true
                persistAsync(finalize) {
                  _ => {
                    alreadyReceived = false
                    coordinator ! AckFinalize(accountId, transaction.transactionId, deliveryId)
                    coordinator = null
                    activeBalance = balance
                    unstashAll()
                    context.become(receiveCommand, discardOld = true) // going back to normal
                  }
                }

              case rollback @ Rollback(accId, t, _) if !alreadyReceived && t.transactionId == transaction.transactionId =>
                if (coordinator == null) coordinator = sender()
                val counterEvent = if (transaction.sourceAccountId == accountId) BalanceChanged(transaction.amount) else BalanceChanged(-transaction.amount)
                alreadyReceived = true
                persistAllAsync(List(counterEvent, rollback)) {
                  case e: BalanceChanged =>
                    onEvent(e)
                    activeBalance = balance
                  case r: Rollback =>
                    alreadyReceived = false
                    coordinator ! AckRollback(accountId, t.transactionId, r.deliveryId)
                    coordinator = null
                    unstashAll()
                    context.become(receiveCommand, discardOld = true) // going back to normal
                }
          }

  var uncompletedTransaction: MoneyTransaction = _

  override def receiveRecover: Receive = {

    case mt: MoneyTransaction =>
      uncompletedTransaction = mt

    case _: Rollback =>
      uncompletedTransaction = null

    case _: Finalize =>
      uncompletedTransaction = null

    case e @ BalanceChanged(_) =>
      onEvent(e)

    case RecoveryCompleted =>
      if (uncompletedTransaction == null) {
        log.info(s"recovery complete, balance is $balance")
        activeBalance = balance
      } else {
        log.info("recovery complete, transaction outstanding")
        activeBalance = previousBalance
        context.become(waitingForFinalizeOrRollback(uncompletedTransaction))
      }
  }



}


