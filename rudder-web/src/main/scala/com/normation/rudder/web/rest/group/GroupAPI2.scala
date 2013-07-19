/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.rest.group

import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.web.rest.RestExtractorService

import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper

class GroupAPI2 (
    readGroup     : RoNodeGroupRepository
  , restExtractor : RestExtractorService
  , apiV2       : GroupApiService2
) extends RestHelper with GroupAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV2.listGroups(req)

    case Put(Nil, req) => {
      val restGroup = restExtractor.extractGroup(req.params)
      apiV2.createGroup(restGroup, req)
    }

    case Get(id :: Nil, req) => apiV2.groupDetails(id, req)

    case Delete(id :: Nil, req) =>  apiV2.deleteGroup(id,req)

    case Post(id:: Nil, req) => {
      val restGroup = restExtractor.extractGroup(req.params)
      apiV2.updateGroup(id,req,restGroup)
    }

    case Post("reload" :: id:: Nil, req) => {
      apiV2.groupReload(id, req)
    }

/*    case id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(arg) =>
          val restGroup = restExtractor.extractGroupFromJSON(arg)
          apiV2.updateGroup(id,req,restGroup)
        case eb:EmptyBox=>    toJsonError(id, "no args arg")("Empty",true)
      }
    }*/

  }
  serve( "api" / "2" / "groups" prefix requestDispatch)


}