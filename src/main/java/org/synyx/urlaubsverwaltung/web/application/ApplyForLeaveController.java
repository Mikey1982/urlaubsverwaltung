package org.synyx.urlaubsverwaltung.web.application;

import org.joda.time.DateMidnight;
import org.joda.time.chrono.GregorianChronology;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Controller;

import org.springframework.ui.Model;

import org.springframework.validation.DataBinder;
import org.springframework.validation.Errors;

import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.synyx.urlaubsverwaltung.core.account.domain.Account;
import org.synyx.urlaubsverwaltung.core.account.service.AccountService;
import org.synyx.urlaubsverwaltung.core.account.service.VacationDaysService;
import org.synyx.urlaubsverwaltung.core.application.domain.Application;
import org.synyx.urlaubsverwaltung.core.application.domain.VacationType;
import org.synyx.urlaubsverwaltung.core.application.service.ApplicationInteractionService;
import org.synyx.urlaubsverwaltung.core.person.Person;
import org.synyx.urlaubsverwaltung.core.person.PersonService;
import org.synyx.urlaubsverwaltung.core.util.DateUtil;
import org.synyx.urlaubsverwaltung.security.SessionService;
import org.synyx.urlaubsverwaltung.web.ControllerConstants;
import org.synyx.urlaubsverwaltung.web.DateMidnightPropertyEditor;
import org.synyx.urlaubsverwaltung.web.PersonPropertyEditor;
import org.synyx.urlaubsverwaltung.web.person.PersonConstants;
import org.synyx.urlaubsverwaltung.web.validator.ApplicationValidator;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * Controller to apply for leave.
 *
 * @author  Aljona Murygina - murygina@synyx.de
 */
@RequestMapping("/application")
@Controller
public class ApplyForLeaveController {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private PersonService personService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private ApplicationInteractionService applicationInteractionService;

    @Autowired
    private VacationDaysService vacationDaysService;

    @Autowired
    private ApplicationValidator applicationValidator;

    @InitBinder
    public void initBinder(DataBinder binder) {

        binder.registerCustomEditor(DateMidnight.class, new DateMidnightPropertyEditor());
        binder.registerCustomEditor(Person.class, new PersonPropertyEditor(personService));
    }


    /**
     * Show form to apply for leave.
     *
     * @param  personId  of the person that applies for leave
     * @param  applyingOnBehalfOfSomeOne  defines if applying for leave on behalf for somebody
     * @param  model  to be filled
     *
     * @return  form to apply for leave
     */
    @RequestMapping(value = "/new", method = RequestMethod.GET)
    public String newApplicationForm(@RequestParam(value = "personId", required = false) Integer personId,
        @RequestParam(value = "appliesOnOnesBehalf", required = false) Boolean applyingOnBehalfOfSomeOne, Model model) {

        Person person;
        Person applier;

        if (personId == null) {
            person = sessionService.getLoggedUser();
            applier = person;
        } else {
            java.util.Optional<Person> personByID = personService.getPersonByID(personId);

            if (!personByID.isPresent()) {
                return ControllerConstants.ERROR_JSP;
            }

            person = personByID.get();
            applier = sessionService.getLoggedUser();
        }

        boolean isApplyingForOneSelf = person.equals(applier);

        // only office may apply for leave on behalf of other users
        if (!isApplyingForOneSelf && !sessionService.isOffice()) {
            return ControllerConstants.ERROR_JSP;
        }

        Optional<Account> holidaysAccount = accountService.getHolidaysAccount(DateMidnight.now().getYear(), person);

        if (holidaysAccount.isPresent()) {
            prepareApplicationForLeaveForm(person, new ApplicationForLeaveForm(), model);
        } else {
            model.addAttribute("notpossible", true);
        }

        if (applyingOnBehalfOfSomeOne != null) {
            model.addAttribute("appliesOnOnesBehalf", true);
        } else {
            model.addAttribute("appliesOnOnesBehalf", false);
        }

        return "application" + "/app_form";
    }


    private void prepareApplicationForLeaveForm(Person person, ApplicationForLeaveForm appForm, Model model) {

        List<Person> persons = personService.getActivePersons()
            .stream()
            .sorted(personComparator())
            .collect(Collectors.toList());

        Optional<Account> account = accountService.getHolidaysAccount(DateMidnight.now(
                    GregorianChronology.getInstance())
                .getYear(), person);

        if (account.isPresent()) {
            model.addAttribute("vacationDaysLeft", vacationDaysService.getVacationDaysLeft(account.get()));
            model.addAttribute(PersonConstants.BEFORE_APRIL_ATTRIBUTE, DateUtil.isBeforeApril(DateMidnight.now()));
        }

        model.addAttribute(PersonConstants.PERSON_ATTRIBUTE, person);
        model.addAttribute(PersonConstants.PERSONS_ATTRIBUTE, persons);
        model.addAttribute("date", DateMidnight.now(GregorianChronology.getInstance()));
        model.addAttribute(ControllerConstants.YEAR_ATTRIBUTE,
            DateMidnight.now(GregorianChronology.getInstance()).getYear());
        model.addAttribute("appForm", appForm);
        model.addAttribute("account", account);
        model.addAttribute("vacTypes", VacationType.values());
    }


    private Comparator<Person> personComparator() {

        return (p1, p2) -> p1.getNiceName().toLowerCase().compareTo(p2.getNiceName().toLowerCase());
    }


    @RequestMapping(value = "/new", method = RequestMethod.POST)
    public String newApplication(@RequestParam(value = "personId", required = false) Integer personId,
        @ModelAttribute("appForm") ApplicationForLeaveForm appForm, RedirectAttributes redirectAttributes,
        Errors errors, Model model) {

        Person applier = sessionService.getLoggedUser();
        Person personToApplyForLeave;

        if (personId == null) {
            personToApplyForLeave = applier;
        } else {
            java.util.Optional<Person> optionalPerson = personService.getPersonByID(personId);

            if (!optionalPerson.isPresent()) {
                return ControllerConstants.ERROR_JSP;
            }

            personToApplyForLeave = optionalPerson.get();
        }

        applicationValidator.validate(appForm, errors);

        if (errors.hasErrors()) {
            prepareApplicationForLeaveForm(personToApplyForLeave, appForm, model);

            if (errors.hasGlobalErrors()) {
                model.addAttribute(ControllerConstants.ERRORS_ATTRIBUTE, errors);
            }

            return "application" + "/app_form";
        }

        Application application = appForm.generateApplicationForLeave();

        Application savedApplicationForLeave = applicationInteractionService.apply(application, applier,
                Optional.ofNullable(appForm.getComment()));

        redirectAttributes.addFlashAttribute("applySuccess", true);

        return "redirect:/web/application/" + savedApplicationForLeave.getId();
    }
}
