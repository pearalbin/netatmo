/*
The MIT License (MIT)

Copyright (c) 2015 Pär A Karlsson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package prkn.netmonitor.netatmo

import java.net.URL
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

import play.api.libs.json.{JsObject, JsValue, Json}
import prkn.netmonitor.netatmo.NetAtmo.AccessToken

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.language.postfixOps
import scala.concurrent.duration._

/**
 * Companion object for the netatmo client
 *
 * Created by pearalbin on 2015-12-26.
 */
object NetAtmo {

  /**
   * The time interval between two measurements
   * @see [[https://dev.netatmo.com/doc/methods/getmeasure]]
   */
  object Scale extends Enumeration {
    type Scale = Value
    val max = Value("max")
    val _30min = Value("30min")
    val _1hour = Value("1hour")
    val _3hours = Value("3hours")
    val _1day = Value("1day")
    val _1week = Value("1week")
    val _1month = Value("1month")
  }


  /**
   * Measurement type
   * @see [[https://dev.netatmo.com/doc/methods/getmeasure]]
   */
  object Mtype extends Enumeration {
    type Mtype = Value
    val temperature = Value("Temperature")
    val co2 = Value("CO2")
    val humidity = Value("Humidity")
    val pressure = Value("Pressure")
    val noise = Value("Noise")
    val rain = Value("Rain")
  }

  /**
   * Represents tokens used to access the REST API
   * @param accessToken Raw access token
   * @param expiresIn Number of seconds until expiration of token from creation
   * @param expires Absolute time of expiration
   * @param refreshToken Token used to refresh the access
   */
  case class AccessToken(accessToken : String, expiresIn : FiniteDuration, expires:Date, refreshToken : String)

  object AccessToken {

    /**
     * Creates an AccessToken, calculating the absolute expiration time based on the current time
     * @param accessToken
     * @param expiresIn
     * @param refreshToken
     * @return
     */
    def apply(accessToken: String, expiresIn: FiniteDuration, refreshToken : String) : AccessToken =
      AccessToken(accessToken, expiresIn, new Date(System.currentTimeMillis()+expiresIn.toMillis), refreshToken)

    /**
     * Creates an AccessToken given an existing raw token.
     * @param accessToken
     * @return
     */
    def apply(accessToken:String) : AccessToken = AccessToken(accessToken,(3 hours),"")

    /**
     * Represents a non-existing token
     * @return
     */
    def none : AccessToken = apply("",-(1 hour),"")
  }

  private def expired(accessToken: AccessToken) = {
    accessToken.expires.before( now() )
  }

  private def aboutToExpire(accessToken: AccessToken) = {
    accessToken.expires.before( fiveMinutesFromNow() )
  }

  private def now() = new Date()
  private def fiveMinutesFromNow() = new Date(System.currentTimeMillis()+(5 minutes).toMillis)

  /**
   * Creates an client given an existing raw access token
   * @param clientID Application ID
   * @param clientSecret Application secret
   * @param aToken Existing access token from already established authentication
   * @param ec ExecutionContext for asynchronous completion of i/o operations
   * @return
   */
  def apply(clientID : String, clientSecret : String, aToken : String)(implicit ec:ExecutionContext) = new NetAtmo(clientID, clientSecret, AccessToken(aToken))

  /**
   * Creates a client
   * @param clientID Application ID
   * @param clientSecret Application secret
   * @param ec ExecutionContext for asynchronous completion of i/o operations
   * @return
   */
  def apply(clientID : String, clientSecret : String)(implicit ec:ExecutionContext) = new NetAtmo(clientID, clientSecret)
}

/**
 * A client for working with the netatmo REST API.
 * Keeps authentication state internally.
 * All operations are non-blocking.
 * See [[https://dev.netatmo.com/doc/intro]] for a general introduction to the netatmo REST API.
 * @param clientID Application ID
 * @param clientSecret Application secret
 * @param aToken Optional existing access token from already established authentication
 * @param ec ExecutionContext for asynchronous completion of i/o operations
 */
class NetAtmo(clientID : String, clientSecret : String, aToken : AccessToken = AccessToken.none)(implicit ec:ExecutionContext) {
  import NetAtmo.Mtype._
  import NetAtmo.Scale._
  import NetAtmo._

  private val requestTokenURL = "https://api.netatmo.net/oauth2/token"
  private val accessToken: AtomicReference[AccessToken] = new AtomicReference(aToken)

  /**
   * Authenticate using user credentials
   * @see See [[https://dev.netatmo.com/doc/authentication/usercred]] for further information
   * @param userName User's name
   * @param password User's password
   * @return The AccessToken, if successful
   */
  def clientCredentials(userName : String, password : String) : Future[AccessToken] = {
    Future {
      val body = s"grant_type=password&client_id=$clientID&client_secret=$clientSecret&username=$userName&password=$password&scope=read_station"
      requestAndStoreToken(body)
    }
  }

  /**
   * Refresh the access token
   * @see See [[https://dev.netatmo.com/doc/authentication/refreshtoken]] for further information
   * @return The AccessToken, if successful
   */
  def refreshToken() : Future[AccessToken] = {
    Future {
      val rToken = accessToken.get.refreshToken
      val body = s"grant_type=refresh_token&refresh_token=$rToken&client_id=$clientID&client_secret=$clientSecret"
      requestAndStoreToken(body)
    }
  }

  private def requestAndStoreToken(body: String): AccessToken = {
    val con = new URL(requestTokenURL).openConnection().asInstanceOf[HttpsURLConnection]
    con.setDoOutput(true)
    con.setRequestMethod("POST")
    con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
    con.getOutputStream.write(body.getBytes("UTF-8"))
    val res = Json.parse(con.getInputStream)
    val token = extractAuthToken(res)
    accessToken.set(token)
    token
  }


  /**
   * Simple method to get station data, right now return the JSON as-is.
   * @see See [[https://dev.netatmo.com/doc/methods/getstationsdata]] for more info.
   * @return The raw (but parsed) json result data.
   */
  def getStationsData(): Future[JsValue] = {
    val resourceURL = "https://api.netatmo.net/api/getstationsdata"
    val aToken = accessToken.get.accessToken
    Future{
      val response = Source.fromURL(s"$resourceURL?access_token=$aToken")
      val json = response.toStream.mkString
      Json.parse(json)
    }
  }

  /**
   * Retrieves measurements from a device or module.
   * @see See [[https://dev.netatmo.com/doc/methods/getmeasure]] for details
   * @param deviceId Device Id
   * @param scale Interval between measurements
   * @param mType Type(s) of measurements
   * @param limit Maximum number of mesurements
   * @param moduleId Module Id
   * @param dateBegin Starting timestamp of measurements
   * @param dateEnd Ending timestamp of measurements
   * @return A map from measurement type to timestamped measurements
   */
  def getMeasure(deviceId: String, scale: Scale, mType: Seq[Mtype],
                 limit: Int = 1024,
                 moduleId: Option[String] = None,
                 dateBegin: Option[Date] = None,
                 dateEnd: Option[Date] = None) : Future[Map[Mtype, Seq[(Date, Double)]]] = {
    val resourceURL = "https://api.netatmo.net/api/getmeasure"
    val optimize = false
    val types = mType.mkString(",")
    val optionals = List(
      moduleId.map(mi => s"module_id=$mi"),
      dateBegin.map(db => s"date_begin=${db.getTime/1000}"),
      dateEnd.map(de => s"date_end=${de.getTime/1000}")
    ).flatten match {
      case Nil => ""
      case l:List[String] => "&"+l.mkString("&")
    }

    val aToken = accessToken.get
    if (expired(aToken))
      Future{ throw new Exception(s"Access token expired at ${aToken.expires}") }
    else {
      if (aboutToExpire(aToken))
        Future { refreshToken() }
      Future {
        val response = Source.fromURL(
          s"$resourceURL?access_token=${accessToken.get.accessToken}&device_id=$deviceId&type=$types&scale=$scale&optimize=$optimize$optionals"
        )
        val json = Json.parse(response.toStream.mkString)
        mType.zip(extractMeasurements(json)).toMap
      }
    }
  }

  private def extractAuthToken(json : JsValue) : AccessToken = {
    val accessToken = (json \ "access_token").as[String]
    val expiresIn = (json \ "expires_in").as[Int]
    val refreshToken = (json \ "refresh_token").as[String]
    AccessToken(accessToken, expiresIn seconds, refreshToken)
  }


  private def extractMeasurements(jsValue: JsValue) : Seq[Seq[(Date, Double)]] = {
    val m = (jsValue \ "body").as[JsObject]
    m.fields.map(t => {
      val date = new Date(java.lang.Long.parseLong(t._1)*1000)
      t._2.as[Seq[Double]].map(d=>(date,d))
    }).transpose
  }
}
