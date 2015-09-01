/**
 * @author Drew Noakes https://drewnoakes.com
 */

/// <reference path="../lib/jquery.d.ts" />

interface JSONViewOptions {
    collapsed: boolean;
}

interface JQuery {
    JSONView(obj: any, options?: JSONViewOptions): void;
}
