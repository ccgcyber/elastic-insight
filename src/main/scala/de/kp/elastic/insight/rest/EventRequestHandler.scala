package de.kp.elastic.insight.rest
/* Copyright (c) 2014 Dr. Krusche & Partner PartG
* 
* This file is part of the Elastic-Insight project
* (https://github.com/skrusche63/elastic-insight).
* 
* Elastic-Insight is free software: you can redistribute it and/or modify it under the
* terms of the GNU General Public License as published by the Free Software
* Foundation, either version 3 of the License, or (at your option) any later
* version.
* 
* Elastic-Insight is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
* A PARTICULAR PURPOSE. See the GNU General Public License for more details.
* You should have received a copy of the GNU General Public License along with
* Elastic-Insight. 
* 
* If not, see <http://www.gnu.org/licenses/>.
*/

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

import java.util.Random

import org.elasticsearch.client.Client
import org.elasticsearch.action.index.IndexResponse

import org.elasticsearch.common.logging.Loggers

import org.elasticsearch.common.settings.Settings

import org.elasticsearch.common.xcontent.ToXContent.Params
import org.elasticsearch.common.xcontent.XContentFactory

import org.elasticsearch.indices.IndexAlreadyExistsException

import org.elasticsearch.action.index.IndexRequest.OpType

import de.kp.elastic.insight.exception.AnalyticsException
import de.kp.elastic.insight.utils.ListenerUtils

import scala.collection.mutable.{ArrayBuffer,HashMap}

class EventRequestHandler(settings:Settings,client:Client) extends RequestHandler {

  val TIMESTAMP_FIELD:String = "timestamp"

  val SITE_FIELD:String = "site"
  val USER_FIELD:String = "user"

  val GROUP_FIELD:String = "group"
  val ITEM_FIELD:String  = "item"

  private val DEFAULT_HEALTH_REQUEST_TIMEOUT:String = "30s"
  private val ERROR_LIST:String = "error.list"
       
  private val maxRetryCount = settings.getAsInt("analytics.rest.retry", 20)

  private val indexCreationLock = new ReentrantLock()
  private val random = new Random()
    
  private val commonPool = new ForkJoinPool()
  private val logger = Loggers.getLogger(getClass(), settings)
 
  
  override def execute(params:Params,listener:OnErrorListener,requestMap:Map[String,Any], paramMap:HashMap[String,Any],chain:RequestHandlerChain) {
   	
    val index = params.param("index")
    val mapping = params.param("type")

    /*
     * We evaluate that an event object is part of the request, and that this object
     * has all required field provided
     */
    val event = requestMap.get("event") match {
      
      case None => throw new AnalyticsException("Event is null.")
      case Some(event) => event.asInstanceOf[Map[String,Any]]
    
    }
    
    /* site */
    val site = event.get(SITE_FIELD) match {
      
      case None => throw new AnalyticsException("Field 'site' is not set.")
      case Some(valu) => valu
      
    }
    /* user */
    val user = event.get(USER_FIELD) match {
      
      case None => throw new AnalyticsException("Field 'user' is not set.")
      case Some(valu) => valu
      
    }
    /* group */
    val group = event.get(GROUP_FIELD) match {
      
      case None => throw new AnalyticsException("Field 'group' is not set.")
      case Some(valu) => valu
      
    }
    /* item */
    val item = event.get(ITEM_FIELD) match {
      
      case None => throw new AnalyticsException("Field 'item' is not set.")
      case Some(valu) => valu
      
    }
 
    try {

      doEventCreation(params,listener,requestMap,paramMap,event,OpType.INDEX,chain)

    } catch {
      case e:Exception => {
        
        val errorList = getErrorList(paramMap)
        if (errorList.size >= maxRetryCount) {
          listener.onError(e)
            
        } else {
          
          sleep(e)
          errorList += e
                
          fork(new Runnable() {
            override def run() {
			  execute(params, listener,requestMap,paramMap,chain)
			}
          })
            
        }
      }
    }
    
  }
    
  private def doEventCreation(params:Params,listener:OnErrorListener,requestMap:Map[String,Any],paramMap:HashMap[String,Any],event:Map[String,Any],opType:OpType,chain:RequestHandlerChain) {
   	
    val index = params.param("index")
    val mapping = params.param("type")
        
    val responseListener = new ListenerUtils.OnIndexResponseListener[IndexResponse]() {
      override def onResponse(response:IndexResponse) {
	    chain.execute(params,listener,requestMap,paramMap)
      }      
    }
		
	val failureListener = new ListenerUtils.OnFailureListener() {
      override def onFailure(t:Throwable) {
	                
		val errorList = getErrorList(paramMap)
	    if (errorList.size >= maxRetryCount) {
	      listener.onError(t)
	      
	    } else {
	        
	      sleep(t)
	      errorList += t
	                    
	      doIndexExists(params,listener,requestMap,paramMap,chain)
	      
	    }
				
	  }

	}
        
    /* Update index operation */
    client.prepareIndex(index, mapping).setSource(event).setRefresh(true).setOpType(opType)
      .execute(ListenerUtils.onIndex(responseListener, failureListener))
    
  }
  
  private def doIndexExists(params:Params,listener:OnErrorListener,requestMap:Map[String,Any], paramMap:HashMap[String,Any],chain:RequestHandlerChain) {
        
    val index = params.param("index")
    try {
      indexCreationLock.lock()
            
      val indicesExistsResponse = client.admin().indices().prepareExists(index)
                                    .execute().actionGet()
            
      if (indicesExistsResponse.isExists()) {
        doMappingCreation(params,listener,requestMap,paramMap,chain)
            
      } else {
        doIndexCreation(params,listener,requestMap,paramMap,chain, index)
            
      }
    } catch {
      case e:Exception => {
            
        val errorList = getErrorList(paramMap)
        if (errorList.size >= maxRetryCount) {
          listener.onError(e)
            
        } else {
          
          sleep(e)
          errorList += e
                
          fork(new Runnable() {
            override def run() {
			  execute(params, listener, requestMap, paramMap,chain)
			}
           })            
        }
      }
       
    } finally {
     indexCreationLock.unlock()
    }
    
  }

  private def doIndexCreation(params:Params,listener:OnErrorListener,requestMap:Map[String,Any], paramMap:HashMap[String,Any],chain:RequestHandlerChain, index:String) {
    
    try {
      
      val createIndexResponse = client.admin().indices().prepareCreate(index)
                                  .execute().actionGet()
            
      if (createIndexResponse.isAcknowledged()) {
        doMappingCreation(params,listener,requestMap,paramMap,chain)
            
      } else {
        listener.onError(new AnalyticsException("Failed to create " + index))
            
      }
    
    } catch {
      
    case e:IndexAlreadyExistsException => 	
        	
        fork(new Runnable() {
          override def run() {
			doIndexExists(params,listener,requestMap,paramMap,chain)
          }
        })
        
      case e:Exception => {
            
        val errorList = getErrorList(paramMap)
        if (errorList.size >= maxRetryCount) {
          listener.onError(e)
            
        } else {
          
          sleep(e)
          errorList += e
        
          fork(new Runnable() {
            override def run() {
    		  execute(params, listener, requestMap, paramMap,chain)
            }				
          })

        }
      }
    }
    
  }
    
  private def doMappingCreation(params:Params,listener:OnErrorListener,requestMap:Map[String,Any], paramMap:HashMap[String,Any],chain:RequestHandlerChain) {

    val index = params.param("index")
    val mapping = params.param("type")
        
    val timeout = params.param("timeout")

    try {
      
      val healthResponse = client.admin().cluster().prepareHealth(index)
                             .setWaitForYellowStatus()
                             .setTimeout(params.param(timeout))
                             .execute().actionGet()
                             
            
      if (healthResponse.isTimedOut()) {
        listener.onError(new AnalyticsException("Failed to create index: " + index + "/" + mapping))
      }

      /*
       * Define mapping schema for index 'index' and 'type'; note, that
       * we actually support the following common schema for rule and
       * also series analysis: timestamp, site, user, group and item.
       * 
       * This schema is compliant to the actual transactional as well
       * as sequence source in spark-arules and spark-fsm
       */
      val builder = XContentFactory.jsonBuilder()
                      .startObject()
                      .startObject(mapping)
                        .startObject("properties")

                          /* timestamp */
                          .startObject(TIMESTAMP_FIELD)
                            .field("type", "long")
                          .endObject()
                    
                          /* site */
                          .startObject(SITE_FIELD)
                            .field("type", "string")
                            .field("index", "not_analyzed")
                          .endObject()

                          /* user */
                          .startObject(USER_FIELD)
                            .field("type", "string")
                            .field("index", "not_analyzed")
                          .endObject()//

                          /* group */
                          .startObject(GROUP_FIELD)
                            .field("type", "string")
                            .field("index", "not_analyzed")
                          .endObject()//

                          /* item */
                          .startObject(ITEM_FIELD)
                            .field("type", "integer")
                          .endObject()

                        .endObject() // properties
                      .endObject()   // mapping
                    .endObject()

      val mappingResponse = client.admin().indices().preparePutMapping(index).setType(mapping).setSource(builder)
                              .execute().actionGet()
            
      if (mappingResponse.isAcknowledged()) {
            	
        fork(new Runnable() {
          
          override def run() {
			execute(params, listener, requestMap, paramMap,chain)
		  }
        })

      } else {
        listener.onError(new AnalyticsException("Failed to create mapping for " + index + "/" + mapping))
            
      }
        
    } catch {
      case e:Exception => listener.onError(e)
    }
    
  }

  private def getErrorList(paramMap:HashMap[String,Any]):ArrayBuffer[Throwable] = {

    var errorList = paramMap.get(ERROR_LIST).asInstanceOf[ArrayBuffer[Throwable]]
    if (errorList == null) {
      errorList = ArrayBuffer.empty[Throwable]
        paramMap += ERROR_LIST -> errorList
    }
        
    errorList
    
  }

  private def fork(task:Runnable) {
    commonPool.execute(task)        
  }
    
  private def sleep(t:Throwable) {
        
    val waitTime = random.nextInt(2500) + 500
    if (logger.isDebugEnabled()) {
      
      val msg = String.format("""Waiting for %s ms and retrying... The cause is: %s""",waitTime.toString,t.getMessage)
      logger.debug(msg,t)
    }
        
    try {
       Thread.sleep(waitTime)
        
    } catch {
      case e1:InterruptedException => {}
        
    }
    
  }

}