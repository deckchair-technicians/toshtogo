/**
 * @author Drew Noakes http://drewnoakes.com
 */

/// <reference path="../lib/handlebars.d.ts" />
/// <reference path="../lib/jquery.d.ts" />

class DOMTemplate
{
    private template: (context?: any, options?: any) => string;

    constructor(private templateId: string)
    {
        var templateElement = document.getElementById(templateId);

        if (!templateElement) {
            console.error('No element found with id', templateId);
            return;
        }

        var templateText = templateElement.textContent;

        console.assert(!!templateText);

        this.template = Handlebars.compile(templateText);
    }

    public create(data?: any): HTMLElement
    {
        return $(this.template(data)).get(0);
    }
}

export = DOMTemplate;
