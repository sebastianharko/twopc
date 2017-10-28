package app

import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Stash, Timers}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId, Passivate}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.{PersistentActor, RecoveryCompleted, ReplyToStrategy, StashOverflowStrategy}
import app.messages._

import scala.concurrent.duration._

/*


See ACCOUNT.txt for the state machine diagram


 */


object AccountActor {

  val NumShards: Int = sys.env.get("NUM_SHARDS_ACCOUNTS").map(_.toInt).getOrElse(100)

  val PassivateAfter: Int = sys.env.get("PASSIVATE_ACCOUNT").map(_.toInt).getOrElse(120)

  val CommitOrAbortTimeout: FiniteDuration = sys.env.get("ACCOUNT_TIMEOUT").map(_.toInt).map(_ milliseconds).getOrElse(2 seconds)

  val extractEntityId: ExtractEntityId = {
    case c @ ChangeBalance(accountId, _, _) => (accountId, c)
    case c @ GetBalance(accountId) => (accountId, c)
    case c @ IsLocked(accountId) => (accountId, c)
    case c @ Vote(accountId, _, _, _, _) => (accountId, c)
    case c @ Commit(accountId, _) => (accountId, c)
    case c @ Abort(accountId, _) => (accountId, c)
    case c @ Rollback(accountId, _, _) => (accountId, c)
    case c @ Finalize(accountId, _, _) => (accountId, c)
  }

  import Math.abs
  val extractShardId: ExtractShardId = {
    case ChangeBalance(accountId, _, _) => (abs(accountId.hashCode) % NumShards).toString
    case GetBalance(accountId) => (abs(accountId.hashCode) % NumShards).toString
    case IsLocked(accountId) => (abs(accountId.hashCode) % NumShards).toString
    case Vote(accountId, _, _, _, _) => (abs(accountId.hashCode) % NumShards).toString
    case Commit(accountId, _) => (abs(accountId.hashCode) % NumShards).toString
    case Abort(accountId, _) => (abs(accountId.hashCode) % NumShards).toString
    case Rollback(accountId, _, _) => (abs(accountId.hashCode) % NumShards).toString
    case Finalize(accountId, _, _) => (abs(accountId.hashCode) % NumShards).toString
  }

  def accountsShardRegion(system: ActorSystem): ActorRef = ClusterSharding(system).start(
    typeName = "account",
    entityProps = Props(new AccountActor()).withMailbox("stash-capacity-mailbox"),
    settings = ClusterShardingSettings(system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)

  def proxyToShardRegion(system: ActorSystem, role:Option[String] = Some("account")): ActorRef = ClusterSharding(system).startProxy(
    typeName = "account",
    role = role,
    extractEntityId = extractEntityId,
    extractShardId = extractShardId
  )


}

case object Stop

class AccountActor() extends PersistentActor with ActorLogging with Timers with Stash {

  override def internalStashOverflowStrategy: StashOverflowStrategy = ReplyToStrategy(AccountStashOverflow(accountId))

  context.setReceiveTimeout(AccountActor.PassivateAfter.seconds)

  override def persistenceId: String = self.path.name

  val accountId: String = self.path.name

  var previousBalance = 0

  var balance = 0

  var activeBalance = balance

  def validate(e: Any): Boolean = {
    e match {
      case ChangeBalance(_, byAmount, _) => activeBalance + byAmount >= 0
    }
  }

  def validateMoneyTransaction(sourceAccountId: String, amount: Int): Boolean = {
    if (sourceAccountId == accountId)
      activeBalance + (-amount) >= 0
    else
      true
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
      sender() ! Balance(activeBalance)
  }

  def canRespondToPreviousTransactionsCoordinators(currentTransactionId: String): Receive = {
    case r: Rollback if  r.transactionId != currentTransactionId =>
      sender() ! AckRollback(accountId, r.deliveryId)

    case f: Finalize if f.transactionId != currentTransactionId =>
      sender() ! AckFinalize(accountId, f.deliveryId)
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
    case c: ChangeBalance if !validate(c) =>
      sender() ! Rejected("na")
  }

  def canVoteNoOnMoneyTransaction: Receive = {
    case Vote(_, _ , sourceAccountId, _ , amount) if !validateMoneyTransaction(sourceAccountId, amount) =>
      sender() ! No(accountId)
  }

  def canStashChangeBalanceCommand: Receive = {
    case c : ChangeBalance if validate(c) =>
      stash()
  }

  def canStashVoteRequest: Receive = {
    case Vote(_, _, sourceAccountId, _, amount) if validateMoneyTransaction(sourceAccountId,amount) =>
      stash()
  }

  def passivationSupport: Receive = {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)
    case Stop => context.stop(self)
  }

  var replyTo: Option[ActorRef] = None // for ChangeBalance
  def canChangeBalance: Receive = {
    case c @ ChangeBalance(_, m, respond) if validate(c) =>
      replyTo = if (respond) Some(sender()) else None
      context.become(whileChangingBalance, discardOld = true)
      persistAsync(BalanceChanged(m)) {
        e => {
          onEvent(e)
          activeBalance = balance
          replyTo.foreach(_ ! Accepted("na"))
          unstashAll()
          context.become(receiveCommand, discardOld = true)
        }
      }
  }

  var coordinator: ActorRef = _
  def canVoteYesOnMoneyTransaction: Receive = {
    case Vote(_, transactionId, sourceAccountId, destinationAccountId, amount) if validateMoneyTransaction(sourceAccountId, amount) =>
      coordinator = sender()
      coordinator ! Yes(accountId)
      timers.startSingleTimer(accountId, TimedOut, AccountActor.CommitOrAbortTimeout)
      context.become(waitingForCommitOrAbortOrTimeout(MoneyTransaction(transactionId, sourceAccountId, destinationAccountId, amount)))
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

            case Abort(_, abortTransactionId) if abortTransactionId == transaction.transactionId =>
              // don't worry about sending an Ack here, since the coordinator can assume it's gonna time out anyway
              timers.cancel(accountId)
              coordinator = null
              unstashAll()
              context.become(receiveCommand, discardOld = true)

            case TimedOut =>
              coordinator = null
              unstashAll()
              context.become(receiveCommand, discardOld = true)

            case Commit(_, commitTransactionId: String) if commitTransactionId == transaction.transactionId =>
              timers.cancel(accountId)
              val event = if (transaction.sourceAccountId == accountId) BalanceChanged(-transaction.amount) else BalanceChanged(transaction.amount)
              persistAllAsync(List(transaction, event)) {
                case _: MoneyTransaction => // do nothing
                case e: BalanceChanged =>
                  context.become(waitingForFinalizeOrRollback(transaction), discardOld = true)
                  onEvent(e)
                  activeBalance = previousBalance
                  coordinator ! AckCommit(accountId)
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
              case finalize @ Finalize(_, transactionId, deliveryId) if !alreadyReceived && transactionId == transaction.transactionId =>
                if (coordinator == null) coordinator = sender()
                alreadyReceived = true
                persistAsync(finalize) {
                  _ => {
                    alreadyReceived = false
                    coordinator ! AckFinalize(accountId, deliveryId)
                    coordinator = null
                    activeBalance = balance
                    unstashAll()
                    context.become(receiveCommand, discardOld = true) // going back to normal
                  }
                }

              case rollback @ Rollback(_, transactionId, _) if !alreadyReceived && transactionId == transaction.transactionId =>
                if (coordinator == null) coordinator = sender()
                val counterEvent = if (transaction.sourceAccountId == accountId) BalanceChanged(transaction.amount) else BalanceChanged(-transaction.amount)
                alreadyReceived = true
                persistAllAsync(List(counterEvent, rollback)) {
                  case e: BalanceChanged =>
                    onEvent(e)
                    activeBalance = balance
                  case r: Rollback =>
                    alreadyReceived = false
                    coordinator ! AckRollback(accountId, r.deliveryId)
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


