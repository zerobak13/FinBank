export function classNames(...classes) {
    return classes.filter(Boolean).join(" ");
}

export const formatCurrency = (value) => {
    return (value || 0).toLocaleString() + "원";
};