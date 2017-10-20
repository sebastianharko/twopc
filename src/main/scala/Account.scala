package app

import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Stash, Timers}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted, ReplyToStrategy, StashOverflowStrategy}
import com.lightbend.cinnamon.metric.{Counter, Rate}




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


  val PassivateAfter: Int = sys.env.get("PASSIVATE_ACCOUNT").map(_.toInt).getOrElse(5 * 60) // ms

  val NumShards: Int = sys.env.get("NUM_SHARDS").map(_.toInt).getOrElse(100)

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

  import Math.abs
  val extractShardId: ExtractShardId = {
    case ChangeBalance(accountId, _) => (abs(accountId.hashCode) % NumShards).toString
    case GetBalance(accountId) => (abs(accountId.hashCode) % NumShards).toString
    case IsLocked(accountId) => (abs(accountId.hashCode) % NumShards).toString
    case Vote(accountId, _) => (abs(accountId.hashCode) % NumShards).toString
    case Commit(accountId, _) => (abs(accountId.hashCode) % NumShards).toString
    case Abort(accountId, _) => (abs(accountId.hashCode) % NumShards).toString
    case Rollback(accountId, _, _) => (abs(accountId.hashCode) % NumShards).toString
    case Finalize(accountId, _, _) => (abs(accountId.hashCode) % NumShards).toString
  }

  def accounts(system: ActorSystem, rate: Rate, stashHitCounter: Counter): ActorRef = ClusterSharding(system).start(
    typeName = "Account",
    entityProps = Props(new AccountActor(rate, stashHitCounter)).withMailbox("stash-capacity-mailbox"),
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)
}


object AccountActor {

  val CommitOrAbortTimeout: FiniteDuration = sys.env.get("ACCOUNT_TIMEOUT").map(_.toInt).map(_ milliseconds).getOrElse(600 milliseconds)

}

case object Stop

class AccountActor(timeoutRate: Rate, stashHitCounter: Counter) extends PersistentActor with ActorLogging with Timers with Stash {

  override def internalStashOverflowStrategy: StashOverflowStrategy = ReplyToStrategy(AccountStashOverflow(accountId))

  import akka.cluster.sharding.ShardRegion.Passivate

  context.setReceiveTimeout(Sharding.PassivateAfter.seconds)

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
    case GetBalance(_) =>
      sender() ! activeBalance
  }

  def canRespondToPreviousTransactionsCoordinators(currentTransactionId: String): Receive = {
    case r: Rollback if  r.transaction.transactionId != currentTransactionId =>
      sender() ! AckRollback(r.accountId, r.transaction.transactionId, r.deliveryId)

    case f: Finalize if f.transaction.transactionId != currentTransactionId =>
      sender() ! AckFinalize(accountId, f.transaction.transactionId, f.deliveryId)
  }

  def showsLocked: Receive = {
    case IsLocked(_) =>
      sender() ! true
  }

  def showsNotLocked: Receive = {
    case IsLocked(_) =>
      sender() ! false
  }

  def canRejectInvalidChangeBalance: Receive = {
    case c @ ChangeBalance(_, _) if !validate(c) =>
      sender() ! Rejected()
  }

  def canVoteNoOnMoneyTransaction: Receive = {
    case Vote(_, moneyTransaction: MoneyTransaction) if !validateMoneyTransaction(moneyTransaction) =>
      sender() ! No(accountId)
  }

  def canStashChangeBalanceCommand: Receive = {

    case c : ChangeBalance if validate(c) =>
      stashHitCounter.increment()
      stash()
  }

  def canStashVoteRequest: Receive = {
    case Vote(_, moneyTransaction) if validateMoneyTransaction(moneyTransaction) =>
      stashHitCounter.increment()
      stash()
  }

  def passivationSupport: Receive = {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)
    case Stop => context.stop(self)
  }

  var replyTo: ActorRef = _ // for ChangeBalance
  def canChangeBalance: Receive = {
    case c @ ChangeBalance(_, m) if validate(c) =>
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
    case Vote(_, moneyTransaction: MoneyTransaction) if validateMoneyTransaction(moneyTransaction) =>
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

            case Abort(_, transId) if transId == transaction.transactionId =>
              // don't worry about sending an Ack here, since the coordinator can assume it's gonna time out anyway
              timers.cancelAll()
              coordinator = null
              unstashAll()
              context.become(receiveCommand, discardOld = true)

            case TimedOut(_) =>
              timeoutRate.mark()
              coordinator = null
              unstashAll()
              context.become(receiveCommand, discardOld = true)

            case Commit(_, tId: String) if tId == transaction.transactionId =>
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
              case finalize @ Finalize(_, t, deliveryId) if !alreadyReceived && t.transactionId == transaction.transactionId =>
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

              case rollback @ Rollback(_, t, _) if !alreadyReceived && t.transactionId == transaction.transactionId =>
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
        activeBalance = balance
      } else {
        activeBalance = previousBalance
        context.become(waitingForFinalizeOrRollback(uncompletedTransaction))
      }
  }



}


