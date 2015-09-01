/**
 * @author Drew Noakes https://drewnoakes.com
 */

interface UrlData
{
    // TODO populate interface with additional functions as required

    segment(index: number): string;

    param(name: string): string;
}

declare function purl(): UrlData;
