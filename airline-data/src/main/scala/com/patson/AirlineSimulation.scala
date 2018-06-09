package com.patson

import com.patson.model._
import com.patson.data._
import scala.collection.mutable._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import com.patson.model.airplane.Airplane

object AirlineSimulation {
  private val AIRLINE_FIXED_COST = 0 //for now...
  private val REPUTATION_INCREMENT = 0.5 
  val MAX_SERVICE_QUALITY_INCREMENT : Double = 1
  
  def airlineSimulation(cycle: Int, linkResult : List[LinkConsumptionDetails], airplanes : List[Airplane]) = {
    //compute profit
    val allAirlines = AirlineSource.loadAllAirlines(true)
    val allLinks = LinkSource.loadAllLinks(LinkSource.ID_LOAD).groupBy { _.airline.id }
    val allTransactions = AirlineSource.loadTransactions(cycle).groupBy { _.airlineId }
    //purge the older transactions
    AirlineSource.deleteTransactions(cycle - 1)
    val linkResultByAirline = linkResult.groupBy { _.airlineId }
    val airplanesByAirline = airplanes.groupBy(_.owner.id)
    val allCountries = CountrySource.loadAllCountries().map( country => (country.countryCode, country)).toMap
    
    val allIncomes = ListBuffer[AirlineIncome]()
     
    val currentCycle = MainSimulation.currentWeek
    allAirlines.foreach { airline =>
        var totalCashRevenue = 0L
        var totalCashExpense = 0L
        var linksDepreciation = 0L
        val linksIncome = linkResultByAirline.get(airline.id) match { 
          case Some(linkConsumptions) => {
            val linksProfit = linkConsumptions.foldLeft(0L)(_ + _.profit)
            val linksAirportFee = linkConsumptions.foldLeft(0L)(_ + _.airportFees)
            val linksCrewCost = linkConsumptions.foldLeft(0L)(_ + _.crewCost)
            val linksFuelCost = linkConsumptions.foldLeft(0L)(_ + _.fuelCost)
            val linksInflightCost = linkConsumptions.foldLeft(0L)(_ + _.inflightCost)
            val linksMaintenanceCost = linkConsumptions.foldLeft(0L)(_ + _.maintenanceCost)
            linksDepreciation = linkConsumptions.foldLeft(0L)(_ + _.depreciation)
            val linksRevenue = linkConsumptions.foldLeft(0L)(_ + _.revenue)
            val linksExpense = linksAirportFee + linksCrewCost + linksFuelCost + linksInflightCost + linksMaintenanceCost + linksDepreciation
            
            totalCashRevenue += linksRevenue
            totalCashExpense += linksExpense - linksDepreciation //airplane depreciation is already deducted on the plane, not a cash expense
            LinksIncome(airline.id, profit = linksProfit, revenue = linksRevenue, expense = linksExpense, ticketRevenue = linksRevenue, airportFee = -1 * linksAirportFee, fuelCost = -1 * linksFuelCost, crewCost = -1 * linksCrewCost, inflightCost = -1 * linksInflightCost, maintenanceCost= -1 * linksMaintenanceCost, depreciation = -1 * linksDepreciation, cycle = currentCycle)
          }
          case None => LinksIncome(airline.id, 0, 0, 0, 0, 0, 0, 0, 0, 0, cycle = currentCycle)
        }
        
        val transactionsIncome = allTransactions.get(airline.id) match {
          case Some(transactions) => {
            var expense = 0L
            var revenue = 0L
            val summary = Map[TransactionType.Value, Long]()
            transactions.foreach { transaction =>
              if (transaction.amount >= 0) { 
                revenue += transaction.amount
              } else {
                expense -= transaction.amount
              }
              
              val existingAmount = summary.getOrElse(transaction.transactionType, 0L)
              summary.put(transaction.transactionType, existingAmount + transaction.amount)
            }
            TransactionsIncome(airline.id, revenue - expense, revenue, expense, capitalGain = summary.getOrElse(TransactionType.CAPITAL_GAIN, 0), createLink = summary.getOrElse(TransactionType.CREATE_LINK, 0), cycle = currentCycle)
          }
          case None => TransactionsIncome(airline.id, 0, 0, 0, capitalGain = 0, createLink = 0, cycle = currentCycle)
        }
        
        
        
        val othersSummary = Map[OtherIncomeItemType.Value, Long]()
        othersSummary.put(OtherIncomeItemType.SERVICE_INVESTMENT, airline.getServiceFunding() * -1)
        totalCashExpense += airline.getServiceFunding()
        
        val baseUpkeep = airline.bases.foldLeft(0L)((upkeep, base) => {
          upkeep + base.getUpkeep 
        })
        
        othersSummary.put(OtherIncomeItemType.BASE_UPKEEP, -1 * baseUpkeep) //negative number
        totalCashExpense += baseUpkeep
        
        val allAirplanesDepreciation = airplanesByAirline.getOrElse(airline.id, List.empty).foldLeft(0L) {
          case(depreciation, airplane) => (depreciation + airplane.depreciationRate)  
        }
        
        val unassignedAirplanesDepreciation = allAirplanesDepreciation - linksDepreciation //account depreciation on planes that are without assigned links
        othersSummary.put(OtherIncomeItemType.DEPRECIATION, unassignedAirplanesDepreciation) //not a cash expense
        
        var othersRevenue = 0L
        var othersExpense = 0L
        othersSummary.foreach { 
          case (_, amount) => {
            if (amount >= 0) {
              othersRevenue += amount
            } else {
              othersExpense -= amount
            }
          }
        }
        
        val othersIncome = OthersIncome(airline.id, othersRevenue - othersExpense, othersRevenue, othersExpense
            , loanInterest = othersSummary.getOrElse(OtherIncomeItemType.LOAN_INTEREST, 0)
            , baseUpkeep = othersSummary.getOrElse(OtherIncomeItemType.BASE_UPKEEP, 0)
            , serviceInvestment = othersSummary.getOrElse(OtherIncomeItemType.SERVICE_INVESTMENT, 0)
            , maintenanceInvestment = othersSummary.getOrElse(OtherIncomeItemType.MAINTENANCE_INVESTMENT, 0)
            , advertisement = othersSummary.getOrElse(OtherIncomeItemType.ADVERTISEMENT, 0)
            , depreciation = othersSummary.getOrElse(OtherIncomeItemType.DEPRECIATION, 0)
            , cycle = currentCycle
        )
        
        val airlineRevenue = linksIncome.revenue + transactionsIncome.revenue + othersIncome.revenue
        val airlineExpense = linksIncome.expense + transactionsIncome.expense + othersIncome.expense
        val airlineProfit = airlineRevenue - airlineExpense
        val airlineWeeklyIncome = AirlineIncome(airline.id, airlineProfit, airlineRevenue, airlineExpense, linksIncome, transactionsIncome, othersIncome, cycle = currentCycle)
        
        allIncomes += airlineWeeklyIncome
        allIncomes ++= computeAccumulateIncome(airlineWeeklyIncome)
        
        val totalCashFlow = totalCashRevenue - totalCashExpense
        airline.setBalance(airline.getBalance() + totalCashFlow) 
          
        //update reputation
        linkResultByAirline.get(airline.id).foreach { linkConsumptions =>
          val totalPassengerKilometers = linkConsumptions.foldLeft(0L) { (foldLong, linkConsumption) =>
            foldLong + linkConsumption.soldSeats.total * linkConsumption.distance
          }
          
          //https://en.wikipedia.org/wiki/World%27s_largest_airlines
          var targetReputation = Math.log(totalPassengerKilometers / 5000) / Math.log(1.2)
          if (targetReputation > Airline.MAX_REPUTATION) {
            targetReputation = Airline.MAX_REPUTATION
          } else if (targetReputation < 10) {
            targetReputation = 10
          }
          
          val currentReputation = airline.getReputation() 
          if (currentReputation < targetReputation) {
            if (currentReputation + REPUTATION_INCREMENT >= targetReputation) {
              airline.setReputation(targetReputation)  
            } else {
              airline.setReputation(currentReputation + REPUTATION_INCREMENT)
            }
          }
        }
        
        //calculate service quality
        allLinks.get(airline.id).foreach {  links =>
          
           val totalCapacity = links.map { _.capacity.total }.sum
           if (totalCapacity > 0) {
             val targetServiceQuality = getTargetQuality(airline.getServiceFunding(), totalCapacity) //50x to get 50 target quality, 200x to get max 100 target quality
             val currentServiceQuality = airline.getServiceQuality()
             airline.setServiceQuality(getNewQuality(currentServiceQuality, targetServiceQuality)) 
           } 
        }
        
        
        
        println(airline + " profit is: " + airlineProfit + " new balance is " + airline.getBalance() + " reputation " +  airline.getReputation())
    }
    
    AirlineSource.saveAirlineInfo(allAirlines)
    IncomeSource.saveIncomes(allIncomes.toList);
    
    //purge previous entry of current year/month
    if (currentCycle % 4 != 0) { //clear previous entry for current month, if currentCycle % 4 == 0, it starts a new entry, so no previous entry for the same month to clear
      IncomeSource.deleteIncomes(currentCycle - 1, Period.MONTHLY)
    }
    if (currentCycle % 52 != 0) { //clear previous entry for current year, if currentCycle % 52 == 0, it starts a new entry, so no previous entry for the same years to clear
      IncomeSource.deleteIncomes(currentCycle - 1, Period.YEARLY)
    }
    
    //purge old entries, keep 10 entries of each Period
    IncomeSource.deleteIncomesBefore(currentCycle - 10, Period.WEEKLY);
    IncomeSource.deleteIncomesBefore(currentCycle - 10 * 4, Period.MONTHLY);
    IncomeSource.deleteIncomesBefore(currentCycle - 10 * 52, Period.YEARLY);
  }
  
  /**
   * compute monthly and yearly income 
   * 
   * Returns Updating income entries
   */
  def computeAccumulateIncome(weeklyIncome : AirlineIncome) : List[AirlineIncome] = {
    //get existing entry
    val currentWeek = MainSimulation.currentWeek
    val airlineId = weeklyIncome.airlineId
    val currentMonthIncomeOption = if (currentWeek % 4 == 0) None else IncomeSource.loadIncomeByAirline(airlineId, currentWeek - 1, Period.MONTHLY)
    
    val updatedMonthIncome = currentMonthIncomeOption match {
      case Some(income) => {
        income.update(weeklyIncome)
      }
      case None => weeklyIncome.copy(period = Period.MONTHLY)//new month
    }
    val currentYearIncomeOption = if (currentWeek % 52 == 0) None else IncomeSource.loadIncomeByAirline(airlineId, currentWeek - 1, Period.YEARLY)
    val updatedYearIncome = currentYearIncomeOption match {
      case Some(income) => {
        income.update(weeklyIncome)
      }
      case None => weeklyIncome.copy(period = Period.YEARLY)//new year
    }
    
    List[AirlineIncome](updatedMonthIncome, updatedYearIncome)
  }
  
  val getTargetQuality : (Int, Int) => Double = (funding : Int, capacity :Int) => {
    val computedQuality = Math.sqrt(funding.toDouble / capacity / 50 ) * 50  //50x capacity to get 50 target quality, 200x capacity to get max 100 target quality
    if (computedQuality >= Airline.MAX_SERVICE_QUALITY) {
      Airline.MAX_MAINTENANCE_QUALITY
    } else {
      computedQuality
    }
  }
  val getNewQuality : (Double, Double) => Double = (currentQuality, targetQuality) =>  {
    val delta = targetQuality - currentQuality
    val adjustment = 
      if (delta >= 0) { //going up, slower when current quality is already high
        MAX_SERVICE_QUALITY_INCREMENT * (1 - (currentQuality / Airline.MAX_SERVICE_QUALITY * 0.9)) //at current quality 0, multiplier 1x; current quality 100, multiplier 0.1x
      } else { //going down, faster when current quality is already high
        -1 * MAX_SERVICE_QUALITY_INCREMENT * (0.1 + (currentQuality / Airline.MAX_SERVICE_QUALITY * 0.9)) //at current quality 0, multiplier 0.1x; current quality 100, multiplier 1x
      }
    if (adjustment >= 0) {
      if (adjustment + currentQuality >= targetQuality) {
        targetQuality
      } else {
        adjustment + currentQuality
      }
    } else {
      if (currentQuality + adjustment <= targetQuality) {
        targetQuality
      } else {
        currentQuality + adjustment
      }
    } 
  }
}