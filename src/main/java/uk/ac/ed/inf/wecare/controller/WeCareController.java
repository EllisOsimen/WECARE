package uk.ac.ed.inf.wecare.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class WeCareController {

	@GetMapping("/")
	public String index() {
		return "redirect:/dashboard.html";
	}

	@GetMapping("/healthz")
	@ResponseBody
	public String healthCheck() {
		return "ok";
	}
}
