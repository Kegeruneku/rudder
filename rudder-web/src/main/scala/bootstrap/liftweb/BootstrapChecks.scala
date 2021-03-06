/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package bootstrap.liftweb

import javax.servlet.UnavailableException
import org.slf4j.LoggerFactory
import net.liftweb.common.Logger
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder

/**
 *
 * Interface of the pipeline for action to execute
 * at bootstrap.
 *
 *
 * Implementation may really be executed when the application
 * is launched, so be careful with the time taken by them.
 *
 * On the other hand, for async bootstraps checks, don't expect
 * order in there execution.
 */
trait BootstrapChecks {
  object logger extends Logger {
    override protected def _logger = LoggerFactory.getLogger("bootchecks")
  }

  def description: String

  @throws(classOf[ UnavailableException ])
  def checks() : Unit

}



class SequentialImmediateBootStrapChecks(checkActions:BootstrapChecks*) extends BootstrapChecks {

  override val description = "Sequence of bootstrap checks"
  val formater = new PeriodFormatterBuilder()
    .appendMinutes()
    .appendSuffix(" m")
    .appendSeparator(" ")
    .appendSeconds()
    .appendSuffix(" s")
    .appendSeparator(" ")
    .appendMillis()
    .appendSuffix(" ms")
    .toFormatter();

  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = checkActions.zipWithIndex.foreach { case (check,i) =>
    val start = System.currentTimeMillis
    val msg = if(logger.isDebugEnabled) {
      s"[#${i}] ${check.description}"
    } else {
      s"${check.description}"
    }
    logger.info(msg)
    check.checks
    logger.debug(msg + s": OK in [${formater.print(new Duration(System.currentTimeMillis - start).toPeriod)}]")
  }

}