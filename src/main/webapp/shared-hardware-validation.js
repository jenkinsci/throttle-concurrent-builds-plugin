/*
 * Client-side sanity checks for the "Shared Hardware Throttling" section of the global configuration:
 *  - the machine assignment label key and the machine max capacity label key must differ;
 *  - machine names in the machine capacities list must be unique.
 * The same rules are enforced on the server in ThrottleJobProperty.DescriptorImpl#configure.
 */
(function () {
  var DEFAULT_LABEL_KEY = "THROTTLE_SHARED_HARDWARE";
  var DEFAULT_MAX_LABEL_KEY = "THROTTLE_SHARED_HARDWARE_MAX";
  var MARKER = "throttleSharedHardwareError";

  function escapeHtml(text) {
    var d = document.createElement("div");
    d.textContent = text;
    return d.innerHTML;
  }

  function validationArea(input) {
    var item = input.closest(".jenkins-form-item");
    return item ? item.querySelector(".validation-error-area") : null;
  }

  function showError(input, message) {
    input.setCustomValidity(message);
    input.dataset[MARKER] = "true";
    var area = validationArea(input);
    if (area && typeof updateValidationArea === "function") {
      updateValidationArea(
        area,
        '<div class="error">' + escapeHtml(message) + "</div>",
      );
    }
  }

  function clearError(input) {
    if (input.dataset[MARKER] !== "true") {
      return;
    }
    delete input.dataset[MARKER];
    input.setCustomValidity("");
    var area = validationArea(input);
    if (area && typeof updateValidationArea === "function") {
      updateValidationArea(area, "<div/>");
    }
  }

  function valueOrDefault(input, fallback) {
    var v = input && input.value ? input.value.trim() : "";
    return v === "" ? fallback : v;
  }

  /** @returns {boolean} true if no problem was found */
  function checkAll() {
    var ok = true;

    var labelInput = document.querySelector('input[name="_.labelKey"]');
    var maxLabelInput = document.querySelector('input[name="_.maxLabelKey"]');
    if (labelInput && maxLabelInput) {
      var a = valueOrDefault(labelInput, DEFAULT_LABEL_KEY);
      var b = valueOrDefault(maxLabelInput, DEFAULT_MAX_LABEL_KEY);
      if (a === b) {
        ok = false;
        var msg =
          'The assignment label key and the max capacity label key must differ (both are "' +
          a +
          '").';
        showError(labelInput, msg);
        showError(maxLabelInput, msg);
      } else {
        clearError(labelInput);
        clearError(maxLabelInput);
      }
    }

    var nameInputs = Array.prototype.slice.call(
      document.querySelectorAll('input[name="_.machineName"]'),
    );
    var counts = {};
    nameInputs.forEach(function (input) {
      var name = input.value.trim();
      if (name !== "") {
        counts[name] = (counts[name] || 0) + 1;
      }
    });
    nameInputs.forEach(function (input) {
      var name = input.value.trim();
      if (name !== "" && counts[name] > 1) {
        ok = false;
        showError(
          input,
          'Machine name "' + name + '" is listed more than once; machine names must be unique.',
        );
      } else {
        clearError(input);
      }
    });

    return ok;
  }

  function relevant(target) {
    return (
      target &&
      target.name &&
      (target.name === "_.labelKey" ||
        target.name === "_.maxLabelKey" ||
        target.name === "_.machineName")
    );
  }

  ["input", "change"].forEach(function (type) {
    document.addEventListener(type, function (event) {
      if (relevant(event.target)) {
        checkAll();
      }
    });
  });

  // Rows added or deleted via the repeatable buttons
  document.addEventListener("click", function (event) {
    if (event.target && event.target.closest && event.target.closest("button")) {
      setTimeout(checkAll, 100);
    }
  });

  // Block saving while there is a problem
  document.addEventListener(
    "submit",
    function (event) {
      if (
        document.querySelector('input[name="_.labelKey"]') &&
        !checkAll()
      ) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    },
    true,
  );

  setTimeout(checkAll, 0);
})();
