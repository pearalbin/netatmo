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

import prkn.netmonitor.netatmo.NetAtmo.Mtype._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


/**
  * Simple example of using the NetAtmo library
  */
object Example1 extends App {

  import scala.concurrent.ExecutionContext.Implicits.global

  //Client Credentials
  val clientID = ???
  val clientSecret = ???

  //User Credentials
  val userName = ???
  val passwd = ???

  val atmo =  new NetAtmo(clientID, clientSecret)
  val res1 = atmo.clientCredentials(userName, passwd)
  res1.onComplete(println(_))
  Await.ready(res1, 10 seconds)

  //Alternative constructor, using an already established token
//  val aToken = "567..."
//  val atmo =  NetAtmo(clientID, clientSecret,  aToken)

  //Explicitly refresh a token
  //  val rt = atmo.refreshToken()
  //  rt.onComplete(println(_))
  //  Await.ready(rt,10 seconds)

  //Get overall station data
  val sdf = atmo.getStationsData
  sdf.onComplete(println(_))

  //Get specific measurements
  val stationId = ??? //"ff:ff:ff:ff:ff:ff"
  val md = atmo.getMeasure(stationId, NetAtmo.Scale._1hour, List(co2, humidity), limit=4) //dateEnd=Some(new Date(1451473200L*1000L)) , dateBegin=Some(new Date(1451386800L*1000L))
  val res = Await.result(md, 5 seconds)
  println(res)
}
