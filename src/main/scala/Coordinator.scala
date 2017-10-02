package app

import akka.actor.{ActorLogging, ActorRef, Timers}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}

import scala.concurrent.duration._


case class MoneyTransaction(id: String, sourceId: String, destinationId: String, amount: Int)

case class Vote(accountId: String, transaction: MoneyTransaction)

case class Yes(entityId: String)

case class No(entityId: String)

case class Commit(accountId: String, tId: String)

case class AckCommit(accountId: String, tId: String)

case class Rollback(accountId: String, transaction: MoneyTransaction, deliveryId: String)

case class AckRollback(accountId: String, tId: String)

case class Finalize(accountId: String, transaction: MoneyTransaction, deliveryId: String)

case class AckFinalize(accountId: String, tId: String)


case object Accepted

case object Rejected


case class TimedOut(id: String)

case class Abort(accountId: String, tId: String)


case class MsgSent(s: Any)
case class MsgConfirmed(deliveryId: Long)

class Coordinator(accounts: ActorRef) extends PersistentActor with ActorLogging with Timers with AtLeastOnceDelivery {

  var t: MoneyTransaction = null
  var replyTo: ActorRef = null

  override def receiveCommand = {
    case m @ MoneyTransaction(tId, sourceId, destinationId, amount) => {
      replyTo = sender()
      t = m
      accounts ! Vote(sourceId, m)
      accounts ! Vote(destinationId, m)
      timers.startSingleTimer("waiting for votes", TimedOut(t.id), 400.milliseconds)
      context.become(waitingForVoteResults, discardOld = true)
    }
  }

  var yesVotes: Set[String] = Set()
  def waitingForVoteResults: Receive = {

    case TimedOut =>
      replyTo ! Rejected
      accounts ! Abort(t.sourceId, t.id)
      accounts ! Abort(t.destinationId, t.id)
      context.stop(self)

    case No(_) =>
      replyTo ! Rejected
      accounts ! Abort(t.destinationId, t.id)
      accounts ! Abort(t.sourceId, t.id)
      timers.cancelAll()
      context.stop(self)

    case Yes(accId) =>
      yesVotes = yesVotes + accId
      if (yesVotes.size == 2) {
          accounts ! Commit(t.destinationId, t.id)
          accounts ! Commit(t.sourceId, t.id)
          timers.cancelAll()
          timers.startSingleTimer("commit acks", TimedOut(t.id), 800.milliseconds)
          context.become(waitingForCommitAcks, discardOld = true)
        }
  }

  var commitAcksReceived: Set[String] = Set()
  def waitingForCommitAcks: Receive = {

    case AckCommit(accountId, tId) =>
      commitAcksReceived = commitAcksReceived + accountId
      if (commitAcksReceived.size == 2) {
        self ! 'Complete
        context.become(complete, discardOld = true)
      }

    case TimedOut(_) =>
      context.become(rollback, discardOld = true)
  }


  def complete: Receive = {
    case 'Complete =>
      deliver(accounts.path)(id => Finalize(ac, ???))
      deliver(accounts.path)(id => Finalize(???, ???))
  }


  def rollback: Receive = {
    case 'Rollback =>
      deliver(accounts.path)(id => Rollback(???, ???))
      deliver(accounts.path)(id => Rollback(???, ???))
  }



  override def receiveRecover: Receive = {
    case _ => ///
  }

  override def persistenceId: String = t.id
}