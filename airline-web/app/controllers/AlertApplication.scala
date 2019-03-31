package controllers

import scala.math.BigDecimal.int2bigDecimal
import com.patson.data.AirlineSource
import com.patson.data.AirplaneSource
import com.patson.data.airplane.ModelSource
import com.patson.model.airplane._
import com.patson.model._
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Writes
import play.api.mvc._
import scala.collection.mutable.ListBuffer
import com.patson.data.CycleSource
import controllers.AuthenticationObject.AuthenticatedAirline
import com.patson.data.CountrySource
import com.patson.data.AirportSource
import play.api.libs.json.Format
import play.api.libs.json.JsResult
import play.api.libs.json.JsSuccess
import com.patson.data.BankSource
import com.patson.model.Loan
import play.api.data.Form
import play.api.data.Forms
import com.patson.data.AlertSource



class AlertApplication extends Controller {
  implicit object AlertWrites extends Writes[Alert] {
    def writes(alert: Alert): JsValue = { 
      var result = JsObject(List(
      "airlineName" -> JsString(alert.airline.name),
      "airlineId" -> JsNumber(alert.airline.id),
      "message" -> JsString(alert.message),
      "category" -> JsNumber(alert.category.id),
      "categoryText" -> JsString(AlertCategory.getDescription(alert.category)),
        "duration" -> JsNumber(alert.duration),
      "cycle" -> JsNumber(alert.cycle)
      ))
      
      alert.targetId.foreach { targetId =>
        result = result + ("targetId" -> JsNumber(targetId))
      }
      
      result
    }
  }
  
  
  val LOG_RANGE = 100 //load 100 weeks worth of alerts
  
  
  def getAlerts(airlineId : Int) = AuthenticatedAirline(airlineId) { request =>
    Ok(Json.toJson(AlertSource.loadAlertsByAirline(request.user.id)))
  }
  
  

  
}
